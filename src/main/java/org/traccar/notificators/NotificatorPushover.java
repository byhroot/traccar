package org.traccar.notificators;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.StatisticsManager;
import org.traccar.helper.LogAction;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.notification.NotificatorManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.traccar.storage.query.Columns;

import java.util.HashMap;
import java.util.Map;

@Singleton
public class NotificatorPushover extends Notificator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificatorSms.class);

    private final Client client;

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2); // 2 thread
                                                                                                   // yeterlidir

    public static class Message {
        @JsonProperty("token")
        private String token;
        @JsonProperty("user")
        private String user;
        @JsonProperty("device")
        private String device;
        @JsonProperty("title")
        private String title;
        @JsonProperty("message")
        private String message;
    }

    private final String apiUrl;
    private final String username;
    private final String password;
    private final StatisticsManager statisticsManager;
    private final Storage storage;

    @Inject
    public NotificatorPushover(Config config, NotificationFormatter notificationFormatter, Client client,
            StatisticsManager statisticsManager, Storage storage) {
        super(notificationFormatter);
        this.client = client;
        this.apiUrl = "https://api.netgsm.com.tr/voicesms/send";
        this.username = config.getString(Keys.NOTIFICATOR_PUSHOVER_USER);
        this.password = config.getString(Keys.NOTIFICATOR_PUSHOVER_TOKEN);
        this.statisticsManager = statisticsManager;
        this.storage = storage;
    }

    @Inject
    private LogAction actionLogger;

    @Inject
    private NotificatorManager notificatorManager;

    @Override
    public void send(User user, NotificationMessage shortMessage, Event event, Position position)
            throws MessageException {
        if (user.getPhone() != null) {
            if (user.hasAttribute("smsLimit")) {
                int smsLimit = user.getInteger("smsLimit"); // smsLimit doğrudan sayı olarak alınıyor
                if (smsLimit > 0) {
                    Message message = new Message();
                    message.title = shortMessage.subject();
                    message.message = shortMessage.digest();
                    int audioId = -1;

                    String titleLower = message.title.toLowerCase();

                    // audioId eşleşmesi
                    if (titleLower.contains("alarm")) {
                        audioId = 104389777;
                    } else if (titleLower.contains("siparis")) {
                        audioId = 104389657;
                    } else if (titleLower.contains("aktivasyon")) {
                        audioId = 104389657;
                    } else if (titleLower.contains("yillik")) {
                        audioId = 104389687;
                    } else if (titleLower.contains("test")) {
                        audioId = 140029795;
                    } else {
                        audioId = -1; // bu değer ile, ses gönderilmeyeceğini anlayacağız
                    }

                    Map<String, Object> jsonData = new HashMap<>();

                    // Header bölümünü oluştur
                    Map<String, String> headerMap = new HashMap<>();
                    headerMap.put("username", username);
                    headerMap.put("password", password);
                    headerMap.put("key", "0");
                    headerMap.put("url", "");
                    headerMap.put("appkey", "");

                    // Seri ve numara bilgileriyle body bölümünü oluştur
                    Map<String, Object> bodyMap = new HashMap<>();
                    Map<String, Object> scenarioMap = new HashMap<>();

                    // Series kısmı
                    Map<String, String> seriesMap = new HashMap<>();
                    seriesMap.put("seri", "1");

                    // Eğer uygun başlık varsa audioid ekle, yoksa text ekle
                    if (titleLower.contains("siparis") || titleLower.contains("aktivasyon")
                            || titleLower.contains("yillik") || titleLower.contains("test")) {
                        seriesMap.put("audioid", String.valueOf(audioId));
                    } else {
                        seriesMap.put("text", "Merhaba, TakipOn'dan bir bildiriminiz var. "
                                + message.message
                                + " Bildirim detaylarını Mobil uygulamadan görüntüleyebilirsiniz. Güvenli Günler Dileriz.");
                    }
                    // Numbers kısmı
                    Map<String, String> numbersMap = new HashMap<>();
                    numbersMap.put("no", user.getPhone());

                    scenarioMap.put("series", new Map[] { seriesMap });
                    scenarioMap.put("numbers", new Map[] { numbersMap });

                    bodyMap.put("scenario", scenarioMap);

                    // JSON ana yapısını birleştir
                    jsonData.put("header", headerMap);
                    jsonData.put("body", bodyMap);

                    try (Response response = client.target(apiUrl)
                            .request()
                            .post(Entity.json(jsonData))) {

                        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                            String responseBody = response.readEntity(String.class);
                            LOGGER.info("API yanıtı: " + responseBody);

                            // Yanıtı ayrıştırma
                            String[] parts = responseBody.split(" ");
                            String statusCode = parts[0]; // İlk kısım durum kodu
                            String messageIdOrError = parts.length > 1 ? parts[1] : ""; // İkinci kısım mesaj ID'si veya
                                                                                        // hata mesajı

                            if ("00".equals(statusCode)) {
                                LOGGER.info("Sesli mesaj başarıyla gönderildi. Mesaj ID: " + messageIdOrError);

                                // 3 dakika sonra durum sorgulama işlemi
                                scheduler.schedule(() -> {
                                    try {
                                        checkMessageStatus(user, messageIdOrError, smsLimit, event, position);
                                    } catch (Exception e) {
                                        LOGGER.warn("Mesaj durumu kontrol edilirken hata: " + e.getMessage());
                                    }
                                }, 2, TimeUnit.MINUTES);

                                // UserLogs modelinde veritabanına kaydetme işlemini yapıyoruz
                                // userlogs.saveToDatabase(user.getId(), "Sesli Mesaj Gönderildi: " + audioId +
                                // " Tel: " + user.getPhone() + " User: " + user.getName() );
                                try {
                                    actionLogger.other(null, user.getId(), "notification", "call", 1,
                                            "Name:" + user.getName() + "Phone:" + user.getPhone(),
                                            "Sesli Mesaj Gönderildi - Durum:" + String.valueOf(statusCode));
                                } catch (Exception e) {
                                    LOGGER.info("SMS DB loglama hatası oldu", e);
                                }
                            } else {
                                handleErrorCodes(statusCode, user);
                                LOGGER.warn("Sesli mesaj Hatası. Mesaj ID: " + messageIdOrError);

                            }
                        } else {
                            LOGGER.warn("Sesli mesaj gönderimi başarısız: " + response.getStatus());
                            throw new MessageException("Sesli mesaj gönderimi başarısız: " + response.getStatus());
                        }
                    }

                } else {
                    LOGGER.warn("SMS limiti bulunmuyor.");
                    throw new MessageException("SMS Limiti Yok" + user.getName());
                }
            } else {
                LOGGER.warn("SMS değeri kullanıcıda tanımlı değil.");
                throw new MessageException("SMS Değeri Tanımlı Değil" + user.getName());
            }
        } else {
            LOGGER.warn("Kullanıcının telefon numarası bulunmuyor." + user.getName());
            throw new MessageException("Kullanıcının telefon numarası bulunmuyor" + user.getName());

        }
    }

    public void sendSystem(User user, NotificationMessage shortMessage, Event event, Position position,
            boolean systemNotify) throws MessageException {
        if (user.getPhone() != null) {
            int smsLimit = user.getInteger("smsLimit"); // smsLimit doğrudan sayı olarak alınıyor
            Message message = new Message();
            message.title = shortMessage.subject();
            message.message = shortMessage.digest();
            int audioId = -1;

            String titleLower = message.title.toLowerCase();

            // audioId eşleşmesi
            if (titleLower.contains("alarm")) {
                audioId = 104389777;
            } else if (titleLower.contains("siparis")) {
                audioId = 104389657;
            } else if (titleLower.contains("aktivasyon")) {
                audioId = 104389657;
            } else if (titleLower.contains("yillik")) {
                audioId = 104389687;
            } else if (titleLower.contains("test")) {
                audioId = 140029795;
            } else {
                audioId = -1; // bu değer ile, ses gönderilmeyeceğini anlayacağız
            }

            Map<String, Object> jsonData = new HashMap<>();

            // Header bölümünü oluştur
            Map<String, String> headerMap = new HashMap<>();
            headerMap.put("username", username);
            headerMap.put("password", password);
            headerMap.put("key", "0");
            headerMap.put("url", "");
            headerMap.put("appkey", "");

            // Seri ve numara bilgileriyle body bölümünü oluştur
            Map<String, Object> bodyMap = new HashMap<>();
            Map<String, Object> scenarioMap = new HashMap<>();

            // Series kısmı
            Map<String, String> seriesMap = new HashMap<>();
            seriesMap.put("seri", "1");

            // Eğer uygun başlık varsa audioid ekle, yoksa text ekle
            if (titleLower.contains("siparis") || titleLower.contains("aktivasyon")
                    || titleLower.contains("yillik") || titleLower.contains("test")) {
                seriesMap.put("audioid", String.valueOf(audioId));
            } else {
                seriesMap.put("text", "Merhaba, TakipOn'dan bir bildiriminiz var. "
                        + message.message
                        + " Bildirim detaylarını Mobil uygulamadan görüntüleyebilirsiniz. Güvenli Günler Dileriz.");
            }
            // Numbers kısmı
            Map<String, String> numbersMap = new HashMap<>();
            numbersMap.put("no", user.getPhone());

            scenarioMap.put("series", new Map[] { seriesMap });
            scenarioMap.put("numbers", new Map[] { numbersMap });

            bodyMap.put("scenario", scenarioMap);

            // JSON ana yapısını birleştir
            jsonData.put("header", headerMap);
            jsonData.put("body", bodyMap);

            try (Response response = client.target(apiUrl)
                    .request()
                    .post(Entity.json(jsonData))) {

                if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                    String responseBody = response.readEntity(String.class);
                    LOGGER.info("API yanıtı: " + responseBody);

                    // Yanıtı ayrıştırma
                    String[] parts = responseBody.split(" ");
                    String statusCode = parts[0]; // İlk kısım durum kodu
                    String messageIdOrError = parts.length > 1 ? parts[1] : ""; // İkinci kısım mesaj ID'si veya hata
                                                                                // mesajı

                    if ("00".equals(statusCode)) {
                        LOGGER.info("Sesli mesaj başarıyla gönderildi. Mesaj ID: " + messageIdOrError);

                        // 2 dakika sonra durum sorgulama işlemi
                        scheduler.schedule(() -> {
                            try {
                                checkMessageStatus(user, messageIdOrError, smsLimit, event, position);
                            } catch (Exception e) {
                                LOGGER.warn("Mesaj durumu kontrol edilirken hata: " + e.getMessage());
                            }
                        }, 2, TimeUnit.MINUTES);

                        // UserLogs modelinde veritabanına kaydetme işlemini yapıyoruz
                        // userlogs.saveToDatabase(user.getId(), "Sesli Mesaj Gönderildi: " + audioId +
                        // " Tel: " + user.getPhone() + " User: " + user.getName() );
                        try {
                            actionLogger.other(null, user.getId(), "notification", "call", 1,
                                    "Name:" + user.getName() + "Phone:" + user.getPhone(),
                                    "Sesli Mesaj Gönderildi - Durum:" + String.valueOf(statusCode));
                        } catch (Exception e) {
                            LOGGER.info("Sesli Mesaj Gönderim Hatası:", e);
                        }
                    } else {
                        handleErrorCodes(statusCode, user);
                        LOGGER.warn("Sesli mesaj Hatası. Mesaj ID: " + messageIdOrError);

                    }
                } else {
                    LOGGER.warn("Sesli mesaj gönderimi başarısız: " + response.getStatus());
                    throw new MessageException("Sesli mesaj gönderimi başarısız: " + response.getStatus());
                }
            }
        } else {
            LOGGER.warn("Kullanıcının telefon numarası bulunmuyor." + user.getName());
            throw new MessageException("Kullanıcının telefon numarası bulunmuyor" + user.getName());

        }
    }

    private void checkMessageStatus(User user, String messageIdOrError, int smsLimit, Event event, Position position) {
        String usercode = username;
        String password = this.password;
        String type = "0";
        String bulkid = messageIdOrError;

        String reportApiUrl = String.format(
                "https://api.netgsm.com.tr/voicesms/report/?usercode=%s&password=%s&bulkid=%s&type=%s",
                usercode, password, bulkid, type);

        try (Response reportResponse = client.target(reportApiUrl)
                .request()
                .get()) {

            if (reportResponse.getStatus() == Response.Status.OK.getStatusCode()) {
                String reportStatus = reportResponse.readEntity(String.class);
                LOGGER.info("Rapor Durumu: " + reportStatus);

                String[] reports = reportStatus.split("<br>");
                boolean isAnswered = false;
                String statusDescription = "Bilinmeyen durum";

                for (String report : reports) {
                    report = report.trim();
                    if (!report.isEmpty()) {
                        String[] details = report.split("\\s+");
                        if (details.length >= 3) {
                            String statusCode = details[2].trim();
                            if ("1".equals(statusCode)) {
                                isAnswered = true;
                                statusDescription = "Çağrı cevaplandı";
                                break;
                            } else {
                                statusDescription = getStatusDescription(statusCode);
                                handleMessageStatus(statusCode);
                                // Ulaşılamadı durumunda SMS gönder
                                if ("3".equals(statusCode)) {
                                    try {
                                        NotificationMessage smsMessage = new NotificationMessage(
                                                "📞 Sesli Bildirim, Ulaşılamadınız.",
                                                "TakipOn'dan bir sesli çağrı bildirimi aldınız ancak ulaşılamadınız Lütfen uygulamadan son bildirimleri kontrol ediniz.",
                                                "",
                                                true);
                                        notificatorManager.getNotificator("sms").send(user, smsMessage, null, null);
                                        notificatorManager.getNotificator("firebase").send(user, smsMessage, event,
                                                position);

                                        LOGGER.info("Ulaşılamadı SMS'i gönderildi: " + user.getPhone());
                                    } catch (Exception e) {
                                        LOGGER.warn("Ulaşılamadı SMS gönderilemedi: " + e.getMessage());
                                    }
                                }

                            }
                        } else {
                            LOGGER.warn("Beklenmeyen rapor formatı: " + report);
                        }
                    }
                }

                int limitChange = isAnswered ? 4 : 1;
                int remainingLimit = smsLimit - limitChange;

                try {
                    user.set("smsLimit", remainingLimit);
                    storage.updateObject(user, new Request(
                            new Columns.Include("attributes"),
                            new Condition.Equals("id", user.getId())));
                    statisticsManager.registerSms();
                    LOGGER.info("SMS limiti güncellendi. Yeni limit: " + remainingLimit);

                    try {
                        actionLogger.other(
                                null,
                                user.getId(),
                                "notification",
                                "callStatus",
                                limitChange,
                                "Name:" + user.getName() + " | Phone:" + user.getPhone(),
                                "Durum: " + statusDescription + " | Kalan Limit: " + remainingLimit // <-- desc2
                        );
                    } catch (Exception e) {
                        LOGGER.info("SMS DB loglama hatası oldu", e);
                    }

                } catch (StorageException e) {
                    LOGGER.warn("SMS limit güncellenirken hata: " + e.getMessage());
                }

            } else {
                LOGGER.warn("Rapor sorgulama başarısız: " + reportResponse.getStatus());
            }
        }
    }

    private String getStatusDescription(String statusCode) {
        switch (statusCode) {
            case "2":
                return "Mesaj cevaplanmadı";
            case "3":
                return "Mesaja veya Numaraya ulaşılamadı";

            case "4":
                return "Ücretlendirme yapılamadı, varlık yetersiz";
            case "5":
                return "Mesaj iptal edildi";
            case "6":
                return "Mesaj başarısız, başlatılamayan çağrı";
            case "7":
                return "Meşgule alındı";
            case "8":
                return "Geçersiz numara";
            case "9":
                return "Mesaj süresi doldu";
            default:
                return "Bilinmeyen durum kodu: " + statusCode;
        }
    }

    private void handleMessageStatus(String statusCode) {
        switch (statusCode) {
            case "2":
                LOGGER.info("Mesaj cevaplanmadı.");
                break;
            case "3":
                LOGGER.warn("Mesaja veya Numaraya ulaşılamadı.");
                break;
            case "4":
                LOGGER.warn("Ücretlendirme yapılamadı. Varlık yetersiz.");
                break;
            case "5":
                LOGGER.warn("Mesaj iptal edildi.");
                break;
            case "6":
                LOGGER.warn("Mesaj başarısız oldu: başlatılamayan çağrılar, durdurulan veya hata alanlar.");
                break;
            case "7":
                LOGGER.warn("Mesaj meşgule alındı.");
                break;
            case "8":
                LOGGER.warn("Geçersiz numara.");
                break;
            case "9":
                LOGGER.warn("Mesaj süresi doldu.");
                break;
            default:
                LOGGER.warn("Bilinmeyen durum kodu: " + statusCode);
                break;
        }
    }

    private void handleErrorCodes(String statusCode, User user) {
        String message;

        switch (statusCode) {
            case "01":
                message = "Mesaj gönderim başlangıç tarihinde hata var. Sistem tarihi ile değiştirildi.";
                break;
            case "02":
                message = "Mesaj gönderim sonlandırılma tarihinde hata var. Sistem tarihi ile değiştirildi.";
                break;
            case "30":
                message = "Geçersiz kullanıcı adı, şifre veya API erişim izni yok.";
                break;
            case "40":
                message = "Ses dosyası bulunamadı.";
                break;
            case "45":
                message = "Gönderilecek telefon numarası bulunamadı.";
                break;
            case "70":
                message = "Hatalı sorgulama. Parametrelerden biri hatalı veya zorunlu alan eksik.";
                break;
            default:
                message = "Bilinmeyen hata kodu: " + statusCode;
                break;
        }
        // Log dosyasına yaz
        LOGGER.warn(message);
        // Veritabanı loglama
        try {
            actionLogger.other(null, user.getId(), "notification", "call", 999,
                    "Name:" + user.getName() + " Phone:" + user.getPhone(), "Durum: " + message + "Code:" + statusCode);
        } catch (Exception e) {
            LOGGER.info("SMS DB loglama hatası oldu", e);
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
        }));
    }
}
