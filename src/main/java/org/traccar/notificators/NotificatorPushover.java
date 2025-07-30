package org.traccar.notificators;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.StatisticsManager;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.model.UserLogs;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.traccar.storage.query.Columns;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

@Singleton
public class NotificatorPushover extends Notificator {

    private static final Logger logger = Logger.getLogger(NotificatorPushover.class.getName());

    private final Client client;

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
    private final String reportApiUrl = "https://api.netgsm.com.tr/voicesms/report";
    private final String username;
    private final String password;
    private final StatisticsManager statisticsManager;
    private final Storage storage;

    
    @Inject
    public NotificatorPushover(Config config, NotificationFormatter notificationFormatter, Client client,
                               StatisticsManager statisticsManager, Storage storage) {
        super(notificationFormatter, "short");
        this.client = client;
        this.apiUrl = "https://api.netgsm.com.tr/voicesms/send";
        this.username = config.getString(Keys.NOTIFICATOR_PUSHOVER_USER);
        this.password = config.getString(Keys.NOTIFICATOR_PUSHOVER_TOKEN);
        this.statisticsManager = statisticsManager;
        this.storage = storage;
    }

    @Override
    public void send(User user, NotificationMessage shortMessage, Event event, Position position)  throws MessageException {
        if (user.getPhone() != null) {
            if (user.hasAttribute("smsLimit")) {
                int smsLimit = user.getInteger("smsLimit");  // smsLimit doğrudan sayı olarak alınıyor
                if (smsLimit > 0) {
                    Message message = new Message();
                    message.title = shortMessage.getSubject();
                    message.message = shortMessage.getBody();
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
                        audioId = 104389720;
                    } else {
                        audioId = -1;  // bu değer ile, ses gönderilmeyeceğini anlayacağız
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
        
                    scenarioMap.put("series", new Map[]{seriesMap});
                    scenarioMap.put("numbers", new Map[]{numbersMap});
        
                    bodyMap.put("scenario", scenarioMap);
        
                    // JSON ana yapısını birleştir
                    jsonData.put("header", headerMap);
                    jsonData.put("body", bodyMap);
        
                    Response response = client.target(apiUrl)
                        .request()
                        .post(Entity.json(jsonData));
        
                    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                        String responseBody = response.readEntity(String.class);
                        logger.info("API yanıtı: " + responseBody);
        
                        // Yanıtı ayrıştırma
                        String[] parts = responseBody.split(" ");
                        String statusCode = parts[0]; // İlk kısım durum kodu
                        String messageIdOrError = parts.length > 1 ? parts[1] : ""; // İkinci kısım mesaj ID'si veya hata mesajı
        
                        if ("00".equals(statusCode)) {
                            logger.info("Sesli mesaj başarıyla gönderildi. Mesaj ID: " + messageIdOrError);
        
                            // 3 dakika sonra durum sorgulama işlemi
                            new Timer().schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    checkMessageStatus(user, messageIdOrError, smsLimit);
                                }
                            }, 60000); // 1 dakika
                            
                            // UserLogs modelinde veritabanına kaydetme işlemini yapıyoruz
                            UserLogs userlogs = new UserLogs(storage);
                            userlogs.saveToDatabase(user.getId(), "Sesli Mesaj Gönderildi: " +  audioId + " Tel: " + user.getPhone() + " User: " + user.getName() );
                            
                        } else {
                            handleErrorCodes(statusCode, user);
                            // UserLogs modelinde veritabanına kaydetme işlemini yapıyoruz
                            UserLogs userlogs = new UserLogs(storage);
                            userlogs.saveToDatabase(user.getId(), "Hata-Sesli Mesaj: " +  statusCode + " Tel: " + user.getPhone() + " User:" + user.getName());
                            logger.warning("Sesli mesaj Hatası. Mesaj ID: " + messageIdOrError);

                        }
                    } else {
                        logger.warning("Sesli mesaj gönderimi başarısız: " + response.getStatus());
                        throw new MessageException("Sesli mesaj gönderimi başarısız: " + response.getStatus());

                    }
                    response.close();
                } else {
                    logger.warning("SMS limiti bulunmuyor.");
                    throw new MessageException("SMS Limiti Yok" + user.getName());
                }
            } else {
                logger.warning("SMS değeri kullanıcıda tanımlı değil.");
                throw new MessageException("SMS Değeri Tanımlı Değil" + user.getName() );
            }
        } else {
            logger.warning("Kullanıcının telefon numarası bulunmuyor." + user.getName() );
            throw new MessageException("Kullanıcının telefon numarası bulunmuyor" + user.getName() );

        }
    }

    private void checkMessageStatus(User user, String messageIdOrError, int smsLimit) {
        // Rapor sorgulama için gerekli parametreleri hazırlıyoruz.
        String usercode = username;
        String password = this.password;
        String type = "0"; // Tek bulkid'ye göre sorgulama
        String bulkid = messageIdOrError;

        // API URL'sini oluşturuyoruz
        String reportApiUrl = String.format(
            "https://api.netgsm.com.tr/voicesms/report/?usercode=%s&password=%s&bulkid=%s&type=%s",
            usercode, password, bulkid, type
        );
    
        // GET isteğini gönderiyoruz
        Response reportResponse = client.target(reportApiUrl)
            .request()
            .get(); // GET isteği
    
    
        if (reportResponse.getStatus() == Response.Status.OK.getStatusCode()) {
            String reportStatus = reportResponse.readEntity(String.class);
            logger.info("Rapor Durumu: " + reportStatus);
        
            // Gelen rapor durumunu parçalama
            String[] reports = reportStatus.split("<br>"); // '<br>' ile ayır
            boolean isAnswered = false;
        
            for (String report : reports) {
                report = report.trim(); // Satırı kırparak boşlukları kaldır
        
                if (!report.isEmpty()) { // Boş satırları atla
                    String[] details = report.split("\\s+"); // Boşluk karakterleri ile ayır
        
                    // Durumu kontrol et
                    if (details.length >= 3) { // 3 eleman var mı?
                        String statusCode = details[2].trim(); // 3. eleman durumu temsil ediyor
        
                        // Cevaplananlar (1) durumunu kontrol et
                        if ("1".equals(statusCode)) {
                            isAnswered = true;
                            break;

                        } else {
                            // Diğer durumları işleme al
                            handleMessageStatus(statusCode);
                        }
                          
                    } else {
                        logger.warning("Beklenmeyen rapor formatı: " + report);
                    }
                }
            }
        
            if (isAnswered) {
                try {
                    user.set("smsLimit", smsLimit - 4); // SMS limitini 1 azalt
                    storage.updateObject(user, new Request(
                            new Columns.Include("attributes"),
                            new Condition.Equals("id", user.getId())));
                    statisticsManager.registerSms();
                    logger.info("Çağrı Cevaplandı - SMS limiti başarıyla güncellendi.");
                } catch (StorageException e) {
                    logger.warning("SMS limit güncellenirken hata: " + e.getMessage());
                }
            } else {
                logger.info("Mesaj cevaplanmadı.");
                    // UserLogs modelinde veritabanına kaydetme işlemini yapıyoruz
                UserLogs userlogs = new UserLogs(storage);
                userlogs.saveToDatabase(user.getId(), "Sesli Mesaj Cevaplanmadı " +  " Tel: " + user.getPhone() + " User:" + user.getName());
                
            }
        } else {
            logger.warning("Rapor sorgulama başarısız: " + reportResponse.getStatus());
        }
        reportResponse.close();
    }
    
    
    
    private void handleMessageStatus(String statusCode) {
        switch (statusCode) {
            case "2":
                logger.info("Mesaj cevaplanmadı.");
                break;
            case "3":
                logger.warning("Mesaja ulaşılamadı.");
                break;
            case "4":
                logger.warning("Ücretlendirme yapılamadı. Varlık yetersiz.");
                break;
            case "5":
                logger.warning("Mesaj iptal edildi.");
                break;
            case "6":
                logger.warning("Mesaj başarısız oldu: başlatılamayan çağrılar, durdurulan veya hata alanlar.");
                break;
            case "7":
                logger.warning("Mesaj meşgule alındı.");
                break;
            case "8":
                logger.warning("Geçersiz numara.");
                break;
            case "9":
                logger.warning("Mesaj süresi doldu.");
                break;
            default:
                logger.warning("Bilinmeyen durum kodu: " + statusCode);
                break;
        }
    }


    private void handleErrorCodes(String statusCode, User user) {
        switch (statusCode) {
            case "01":
                logger.warning("Mesaj gönderim başlangıç tarihinde hata var. Sistem tarihi ile değiştirildi.");
                break;
            case "02":
                logger.warning("Mesaj gönderim sonlandırılma tarihinde hata var. Sistem tarihi ile değiştirildi.");
                break;
            case "30":
                logger.warning("Geçersiz kullanıcı adı, şifre veya kullanıcınızın API erişim izni yok.");
                break;
            case "40":
                logger.warning("Ses dosyası bulunamadı.");
                break;
            case "45":
                logger.warning("Gönderilecek telefon numarası bulunamadı.");
                break;
            case "70":
                logger.warning("Hatalı sorgulama. Gönderdiğiniz parametrelerden biri hatalı veya zorunlu alanlardan biri eksik.");
                break;
            default:
                logger.warning("Bilinmeyen hata kodu: " + statusCode);
                break;
        }
    }

}
