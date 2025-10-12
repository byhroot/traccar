package org.traccar.api.resource;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.json.JSONObject; // Eğer yoksa basit String parse de yapabiliriz

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.model.User;
import org.traccar.model.UserLogs;
import org.traccar.notification.NotificationMessage;
import org.traccar.sms.SmsManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;


import org.traccar.notification.NotificationMessage;
import org.traccar.notification.NotificatorManager;
import java.util.*;
import java.util.concurrent.*;


@Path("registerotp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Singleton
public class RegisterotpResource extends BaseResource {
    @Inject
    private NotificatorManager notificatorManager;
    
    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterotpResource.class);

    private static final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    private static final int EXPIRATION_SECONDS = 180;

    private static final Map<String, List<Long>> smsRequestLog = new ConcurrentHashMap<>();

    private String getClientIp() {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        }
        // Eğer proxy zinciri varsa ilk IP'yi alın
        if (clientIp != null && clientIp.contains(",")) {
            clientIp = clientIp.split(",")[0].trim();
        }
        return clientIp;
    }
    
    static {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();

            otpStore.entrySet().removeIf(e -> e.getValue().isExpired());

            smsRequestLog.entrySet().removeIf(entry -> {
                List<Long> timestamps = entry.getValue();
                timestamps.removeIf(t -> now - t > 60 * 60 * 1000L);
                return timestamps.isEmpty();
            });
        }, 5, 60, TimeUnit.SECONDS);
    }

    private static class OtpEntry {
        String code;
        long timestamp;

        OtpEntry(String code) {
            this.code = code;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > EXPIRATION_SECONDS * 1000L;
        }
    }

    @Context
    private HttpServletRequest request;

    @Inject
    private SmsManager smsManager;

    @Inject
    private Storage storage;
    @POST
    @Path("/")
    @PermitAll
    public Map<String, Object> sendOtp(@QueryParam("phone") String phone) throws StorageException {
        Map<String, Object> result = new HashMap<>();
        
        if (phone == null || phone.isEmpty()) {
            result.put("valid", false);
            result.put("message", "Phone number is required");
            return result;
        }
    
        String clientIp = getClientIp();
    
        try {
            String apiKey = "API_KEY";  // Kendi API anahtarınızı buraya yazın
            String apiUrl = "https://proxycheck.io/v2/" + clientIp + "?key=" + apiKey + "&vpn=1&asn=1";
    
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .build();
    
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            JSONObject json = new JSONObject(response.body());
            JSONObject ipInfo = json.optJSONObject(clientIp);
    
            if (ipInfo != null) {
                String isocode = ipInfo.optString("isocode", "");
                String proxy = ipInfo.optString("proxy", "yes");  // Varsayılan proxy varsa 'yes'
    
                if (!"TR".equalsIgnoreCase(isocode) || !"no".equalsIgnoreCase(proxy)) {
                    LOGGER.warn("VPN/Proxy bağlantısı tespit edildi. IP: {}, Proxy durumu: {}, Ülke Kodu: {}", clientIp, proxy, isocode);
                    result.put("valid", false);
                    result.put("message", "VPN/PROXY Tespit Edildi, IP Adresiniz engellendi.");
                    return result;
                }
    
                String country = ipInfo.optString("country", "Türkiye");
                LOGGER.info("Güvenli bağlantı tespit edildi. Ülke: {}", country);
    
            } else {
                LOGGER.warn("IP bilgisi çözümlenemedi: {}", clientIp);
                result.put("valid", false);
                result.put("message", "Konum Bilgisi Alınamadı, Lütfen daha sonra tekrar deneyin");
                return result;
            }
    
        } catch (Exception e) {
            LOGGER.warn("ProxyCheck API sorgusunda hata: {}", e.getMessage());
            result.put("valid", false);
            result.put("message", "Doğrulama Kodu Gönderilemedi lütfen daha sonra tekrar deneyin.");
            return result;
        }
    
        // Rate limit ve temizleme işlemi
        final int MAX_SMS_PER_HOUR = 2;
        final long WINDOW_MILLIS = 60 * 60 * 1000L; // 1 saat
        long now = System.currentTimeMillis();
    
        smsRequestLog.entrySet().removeIf(entry -> {
            List<Long> timestamps = entry.getValue();
            timestamps.removeIf(t -> now - t > WINDOW_MILLIS); // Eski zamanları temizle
            return timestamps.isEmpty(); // Eğer liste boş kaldıysa IP'yi tamamen sil
        });
    
        List<Long> timestamps = smsRequestLog.computeIfAbsent(clientIp, k -> new ArrayList<>());
        if (timestamps.size() >= MAX_SMS_PER_HOUR) {
            LOGGER.warn("SMS limiti aşıldı -> ip={}, phone={}", clientIp, phone);
            result.put("valid", false);
            result.put("message", "SMS Limiti aşıldı. 1 saat sonra tekrar deneyin.");
            return result;
        }
        timestamps.add(now); // Şu anki zamanı ekle
    
        // OTP kodu oluştur
        String code = String.format("%06d", new Random().nextInt(999999));
        String id = UUID.randomUUID().toString();
    
        otpStore.put(id, new OtpEntry(code));
    
        // SMS gönderimi // işlemler için ntotificator firebase çalışılacak...
        try {
            smsManager.sendMessage(phone, "Hesap olusturma Doğrulama Kodunuz: " + code , false);
        } catch (Exception e) {
            LOGGER.error("SMS gönderilemedi: {}", e.getMessage(), e);
            result.put("valid", false);
            result.put("message", "SMS gönderimi başarısız");
            return result;
        }
    
        // Loglama
        LOGGER.info("OTP gönderildi -> phone={}, code={}, id={}, ip={}", phone, code, id, clientIp);
    
        UserLogs userLogs = new UserLogs(storage);
        userLogs.saveToDatabase(0, "OTP SMS gönderildi -> Tel: " + phone + ", Kod: " + code + ", IP: " + clientIp);
    
        result.put("valid", true);
        result.put("message", "OTP gönderildi");
        result.put("id", id);
        return result;
    }
    
    @POST
    @Path("{id}")
    @PermitAll
    public Map<String, Object> verifyOtp(@PathParam("id") String id, Map<String, String> input) throws StorageException {
        if (!input.containsKey("code")) {
            throw new BadRequestException("Code is required");
        }
        String clientIp = getClientIp();

        String submittedCode = input.get("code");
        OtpEntry entry = otpStore.get(id);

        boolean valid = false;
        String reason;

        if (entry == null) {
            reason = "ID bulunamadı veya süre dolmuş";
        } else if (entry.isExpired()) {
            otpStore.remove(id);
            reason = "Kodun süresi dolmuş";
        } else if (!entry.code.equals(submittedCode)) {
            reason = "Hatalı kod";
        } else {
            valid = true;
            reason = "Kod doğrulandı";
            otpStore.remove(id);
        }
                        
        // Admin kullanıcıları çek
        Request request = new Request(
            new Columns.All(), // Tüm kolonlar
            new Condition.Equals("administrator", true) // administrator alanı true olanları al
        );

        List<User> adminUsers = storage.getObjects(User.class, request);
        // Notification gönderme
        for (User admin : adminUsers) {
            try {
                String subject = "Yeni OTP Doğrulama";
                String body = "Kullanıcı bir OTP doğrulaması gerçekleştirdi: ID numarası:" + id + " Teşekürler.";

                NotificationMessage message = new NotificationMessage(subject, body);

                notificatorManager.getNotificator("firebase").send(admin, message, null, null);
            } catch (Exception e) {
                LOGGER.warn("Notification admin {} gönderilemedi: {}", admin.getId(), e.getMessage());
            }
        }

        LOGGER.info("OTP doğrulama -> id={}, geçerli={}, sebep={}, ip={}", id, valid, reason, clientIp);

        UserLogs userLogs = new UserLogs(storage);
        userLogs.saveToDatabase(0, "OTP doğrulama -> id=" + id + ", geçerli=" + valid + ", sebep=" + reason + ", IP=" + clientIp);

        return Map.of("valid", valid, "message", reason);
    }
}
