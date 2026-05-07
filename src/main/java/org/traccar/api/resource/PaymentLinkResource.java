package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.model.PaymentRequest;
import org.traccar.model.User;
import org.traccar.notification.NotificationMessage;
import org.traccar.notification.NotificatorManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.Storage;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.sms.SmsManager;
import org.traccar.database.StatisticsManager;
import org.traccar.mail.MailManager;
import org.traccar.model.Device;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Path("paylinks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PaymentLinkResource extends BaseResource {
    private static final Logger logger = LoggerFactory.getLogger(PaymentLinkResource.class);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final Storage storage;
    private final Client client;
    private final SmsManager smsManager;
    private final StatisticsManager statisticsManager;
    private final MailManager mailManager;

    private final String API_KEY;
    private final String SITE_ID;
    private final int YILLIKPRICE;
    private final int PROPRICE;

    @Inject
    private NotificatorManager notificatorManager;

    private void processPaidRequest(PaymentRequest request) throws StorageException {
        // Tekrar işlemeyi önle
        if (request.isProcessed()) {
            logger.info("PaymentRequest {} zaten işlenmiş, atlanıyor.", request.getId());
            return;
        }

        // Wix'ten status sorgula
        try {
            String url = "https://www.wixapis.com/payment-links/v1/payment-links/" + request.getPaylinkId();
            Response response = client.target(url)
                    .request()
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("wix-site-id", SITE_ID)
                    .get();

            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                String resp = response.readEntity(String.class);
                response.close();

                org.json.JSONObject obj = new org.json.JSONObject(resp);
                String status = obj.getJSONObject("paymentLink").getString("status");

                // Sadece PAID ise işlem yap
                if ("PAID".equalsIgnoreCase(status)) {
                    request.setStatus("PAID");
                    request.setUpdatedAt(new Date());
                    storage.updateObject(request, new Request(
                            new Columns.Include("status", "updatedAt"),
                            new Condition.Equals("id", request.getId())));

                    // Cihazları güncelle
                    updateDevicesExpiration(request);

                    // PaymentRequest’i processed yap
                    request.setProcessed(true);
                    request.setActive(false);
                    request.setUpdatedAt(new Date());
                    storage.updateObject(request, new Request(
                            new Columns.Include("status", "active", "processed", "updatedAt"),
                            new Condition.Equals("id", request.getId())));
                    logger.info("PaymentRequest {} PAID olarak işlendi.", request.getId());
                } else {
                    // PAID değilse sadece status güncellenebilir
                    request.setStatus(status);
                    request.setUpdatedAt(new Date());
                    storage.updateObject(request, new Request(
                            new Columns.Include("status", "updatedAt"),
                            new Condition.Equals("id", request.getId())));
                    User currentUser = permissionsService.getUser(getUserId());

                    NotificationMessage message = new NotificationMessage(
                            "Link Ödemeniz Bekliyor..", "Cihaz Tarihleri Güncellenmedi.",
                            "Cihaz Tarihleri Güncellenmedi.", true);
                    notificatorManager.getNotificator("firebase").send(currentUser, message, null, null);
                    logger.info("PaymentRequest {} henüz ödenmemiş. Status güncellendi: {}", request.getId(), status);
                }
            } else {
                String error = response.readEntity(String.class);
                response.close();
                throw new StorageException("Durum sorgulama hatası: " + error);
            }
        } catch (Exception e) {
            throw new StorageException("Durum sorgulama hatası: " + e.getMessage(), e);
        }
    }

    // Device expiration ve notification güncellemesini ayrı metod yaptık
    private void updateDevicesExpiration(PaymentRequest request) throws StorageException {
        User currentUser = permissionsService.getUser(request.getUserid());
        if (currentUser == null) {
            throw new StorageException("User not found for PaymentRequest " + request.getId());
        }

        List<Long> deviceIds = new ArrayList<>();
        if (request.getDevices() != null && !request.getDevices().isEmpty()) {
            String devicesStr = request.getDevices().replaceAll("[{}\\s]", "");
            for (String idStr : devicesStr.split(",")) {
                try {
                    deviceIds.add(Long.parseLong(idStr));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid deviceId in request {}: {}", request.getId(), idStr);
                }
            }
        }

        Date now = new Date();
        for (Long deviceId : deviceIds) {
            Device device = storage.getObject(Device.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("id", deviceId)));
            if (device != null) {
                Date currentExp = device.getExpirationTime();
                Calendar cal = Calendar.getInstance();
                boolean shouldUpdate = false;

                if (currentExp == null || currentExp.before(now)) {
                    shouldUpdate = true;
                    cal.setTime(now);
                } else {
                    cal.setTime(currentExp);
                    Calendar checkCal = Calendar.getInstance();
                    checkCal.setTime(currentExp);
                    checkCal.add(Calendar.DAY_OF_MONTH, -30);
                    if (checkCal.getTime().before(now)) {
                        shouldUpdate = true;
                        cal.setTime(currentExp);
                    }
                }

                if (shouldUpdate) {
                    cal.add(Calendar.YEAR, 1);
                    Date newExp = cal.getTime();
                    device.setExpirationTime(newExp);
                    storage.updateObject(device, new Request(
                            new Columns.Include("expirationTime"),
                            new Condition.Equals("id", deviceId)));

                    // Notification gönder
                    try {
                        NotificationMessage message = new NotificationMessage(
                                "Ödeme Tamamlandı", "",
                                "Cihaz Tarihleri Güncellendi.", true);
                        notificatorManager.getNotificator("firebase").send(currentUser, message, null, null);
                    } catch (Exception e) {
                        logger.warn("Notification gönderilemedi: {}", e.getMessage());
                    }

                    logger.info("Device {} expiration güncellendi -> {} -> {}", deviceId, currentExp, newExp);
                } else {
                    logger.info("Device {} expiration 30 günden fazla, güncelleme yapılmadı -> {}", deviceId,
                            currentExp);
                }
            }
        }
    }

    @Inject
    public PaymentLinkResource(Config config, Storage storage, Client client, SmsManager smsManager,
            StatisticsManager statisticsManager, MailManager mailManager) {
        this.storage = storage;
        this.client = client;
        this.smsManager = smsManager;
        this.statisticsManager = statisticsManager;
        this.mailManager = mailManager;

        // Config üzerinden gerçek değerleri alıyoruz
        this.API_KEY = config.getString(Keys.PAYLINK_APIKEY);
        this.SITE_ID = config.getString(Keys.PAYLINK_SITEID);
        this.YILLIKPRICE = config.getInteger(Keys.PAYLINK_YILLIKPRICE);
        this.PROPRICE = config.getInteger(Keys.PAYLINK_PROFARKPRICE);

    }

    @POST
    @Path("create")
    public PaymentRequest create(PaymentRequest request) throws StorageException {
        User currentUser = permissionsService.getUser(getUserId());
        if (currentUser == null) {
            throw new StorageException("Current user not found");
        }
        logger.info("PaymentLink create started for userId: {}", currentUser);

        try {
            // Expiration tarihi 7 gün sonrası
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, 7);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            String expiration = sdf.format(cal.getTime());

            List<Long> deviceIds = request.getDeviceIds(); // frontendden device ID listesi
            Map<String, Integer> paket = request.getPaket(); // {standard:0, pro:1}

            // Device ID’lerini string olarak devices alanına set et
            if (deviceIds != null && !deviceIds.isEmpty()) {
                request.setDevices("{" + deviceIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",")) + "}");
            } else {
                request.setDevices("{}");
            }
            if (paket != null) {
                request.setPackageName(new org.json.JSONObject(paket).toString());
            } else {
                request.setPackageName("{}");
            }

            // logger.info("Device IDs: {}", deviceIds);
            // logger.info("Paket info: {}", paket);

            int totalAmount = 0;
            List<org.json.JSONObject> lineItems = new ArrayList<>();
            StringBuilder noteBuilder = new StringBuilder();

            noteBuilder.append("Yıllık Data Yenileme: ");
            // Device bilgilerini lineItems'a ekle
            for (Long deviceId : deviceIds) {
                Device device = storage.getObject(Device.class, new Request(
                        new Columns.All(),
                        new Condition.Equals("id", deviceId)));
                if (device != null) {
                    noteBuilder.append(device.getName()).append(", ");
                    org.json.JSONObject item = new org.json.JSONObject();
                    item.put("type", "CUSTOM");

                    org.json.JSONObject customItem = new org.json.JSONObject();
                    customItem.put("quantity", 1);
                    customItem.put("name", device.getName());
                    customItem.put("description", "Cihaz ödemesi");
                    customItem.put("price", YILLIKPRICE);

                    org.json.JSONObject physicalProperties = new org.json.JSONObject();
                    physicalProperties.put("weight", 1);
                    physicalProperties.put("sku", "DEVICE-" + device.getId());
                    physicalProperties.put("shippable", false);

                    customItem.put("physicalProperties", physicalProperties);
                    item.put("customItem", customItem);

                    lineItems.add(item);
                    totalAmount += YILLIKPRICE;
                }
            }

            // Paketleri lineItems'a ekle

            if (paket.get("pro") != null && paket.get("pro") > 0) {
                noteBuilder.append("Pro Paket x").append(paket.get("pro")).append(", ");
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("type", "CUSTOM");

                org.json.JSONObject customItem = new org.json.JSONObject();
                customItem.put("quantity", paket.get("pro"));
                customItem.put("name", "Pro Paket");
                customItem.put("description", "Pro paket ödemesi");
                customItem.put("price", PROPRICE);

                org.json.JSONObject physicalProperties = new org.json.JSONObject();
                physicalProperties.put("weight", 1);
                physicalProperties.put("sku", "PRO");
                physicalProperties.put("shippable", false);

                customItem.put("physicalProperties", physicalProperties);
                item.put("customItem", customItem);

                lineItems.add(item);
                totalAmount += paket.get("pro") * PROPRICE;
            }

            // ✅ BURAYA - totalAmount artık tam, noteBuilder henüz bitmedi
            int discountAmount = 0;
            try {
                String contactId = getWixContactId(currentUser.getEmail());
                int loyaltyPoints = getWixLoyaltyBalance(contactId);
                discountAmount = Math.min(loyaltyPoints / 10, totalAmount);
                totalAmount -= discountAmount;
                logger.info("Loyalty indirimi uygulandı: {} TL ({} puan)", discountAmount, discountAmount * 10);

                if (discountAmount > 0) {
                    resetLoyaltyPoints(contactId); // ✅ puan sıfırla
                }
            } catch (Exception e) {
                logger.warn("Loyalty indirimi alınamadı, indirimsiz devam: {}", e.getMessage());
            }
            // 👇 BURAYA EKLE - discount sonrası lineItems fiyatlarını güncelle
            if (discountAmount > 0) {
                int remaining = discountAmount;
                for (int i = lineItems.size() - 1; i >= 0 && remaining > 0; i--) {
                    org.json.JSONObject ci = lineItems.get(i).getJSONObject("customItem");
                    int itemPrice = ci.getInt("price");
                    int deduct = Math.min(itemPrice - 1, remaining); // minimum 1 TL kalmalı (gt:0)
                    ci.put("price", itemPrice - deduct);
                    remaining -= deduct;
                }
            }

            // Son virgülü kaldır
            if (noteBuilder.length() > 2) {
                noteBuilder.setLength(noteBuilder.length() - 2);
            }
            // ✅ BU SATIR EKSİK - ekle
            if (discountAmount > 0) {
                noteBuilder.append(" | Puan İndirimi: -").append(discountAmount).append(" TL");
            }
            // Wix payload objesi
            org.json.JSONObject payload = new org.json.JSONObject();
            org.json.JSONObject paymentLink = new org.json.JSONObject();
            paymentLink.put("title", "TakipOn - " + currentUser.getName() + " - Link Ödeme");
            paymentLink.put("description", noteBuilder.toString());
            paymentLink.put("currency", "TRY");
            paymentLink.put("expirationDate", expiration);
            paymentLink.put("paymentsLimit", "1"); // string olarak
            paymentLink.put("type", "ECOM");

            paymentLink.put("price", String.valueOf(totalAmount)); // string olarak

            org.json.JSONObject ecomPaymentLink = new org.json.JSONObject();
            for (org.json.JSONObject item : lineItems) {
                org.json.JSONObject customItem = item.getJSONObject("customItem");
                customItem.put("price", String.valueOf(customItem.getInt("price")));
            }
            ecomPaymentLink.put("lineItems", lineItems);
            paymentLink.put("ecomPaymentLink", ecomPaymentLink);

            org.json.JSONObject note = new org.json.JSONObject();
            note.put("text", noteBuilder.toString());
            paymentLink.put("note", note);

            payload.put("paymentLink", paymentLink);

            // logger.info("Wix payload: {}", payload.toString());

            Response response = client.target("https://www.wixapis.com/payment-links/v1/payment-links")
                    .request()
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("wix-site-id", SITE_ID)
                    .post(Entity.json(payload.toString()));

            // logger.info("Wix response status: {}", response.getStatus());

            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                String resp = response.readEntity(String.class);
                response.close();

                // logger.info("Wix response body: {}", resp);
                org.json.JSONObject obj = new org.json.JSONObject(resp);
                org.json.JSONObject paymentLinkObj = obj.getJSONObject("paymentLink");

                String paylinkUrl = paymentLinkObj.getJSONObject("links").getJSONObject("url").getString("url");
                String paylinkId = paymentLinkObj.getString("id");

                request.setPaylink(paylinkUrl);
                request.setPaylinkId(paylinkId);
                request.setStatus("CREATED");
                request.setProcessed(false);
                request.setActive(true);
                request.setCreatedAt(new Date());
                request.setUpdatedAt(new Date());
                request.setTotalAmount(totalAmount);

                // User ID’yi request objesine set ediyoruz
                request.setUserid(currentUser.getId());

                // Veritabanına kaydet
                request.setId(storage.addObject(
                        request,
                        new Request(new Columns.Exclude("id", "deviceIds", "paket", "attributes"))));

                // SMS gönderimi
                if (currentUser.getPhone() != null && !currentUser.getPhone().isEmpty()) {
                    String smsMessage = "Guvenli Odeme linkiniz: " + paylinkUrl;
                    statisticsManager.registerSms();
                    smsManager.sendMessage(currentUser.getPhone(), smsMessage, false);
                }
                String mailSubject = "TakipOn | Ödeme linkiniz ";

                StringBuilder mailBodyBuilder = new StringBuilder();
                mailBodyBuilder.append("Sayın ").append(currentUser.getName()).append(",\n\n")
                        .append("Siparişiniz başarıyla oluşturulmuştur. Detayları aşağıda bulabilirsiniz:\n\n")
                        .append("📌 Seçilen Araçlar / Paketler:\n")
                        .append(noteBuilder.toString()).append("\n\n")
                        .append("💰 Toplam Tutar: ").append(totalAmount).append(" TL\n\n")
                        .append("Ödeme işleminizi aşağıdaki bağlantı üzerinden güvenle gerçekleştirebilirsiniz:\n")
                        .append(paylinkUrl).append("\n\n")
                        .append("Sipariş sonrası aktivasyonlar 24 saat içerisinde otomatik gerçekleştirilecektir. Teşekkür eder, iyi günler dileriz.\n")
                        .append("TakipOn Ekibi");
                String mailBody = mailBodyBuilder.toString();
                if (currentUser != null) {
                    try {
                        mailManager.sendMessage(currentUser, true, mailSubject, mailBody);
                    } catch (Exception e) {
                        logger.warn("Mail gönderilemedi: {}", e.getMessage());
                    }
                }
                // create metodunun sonunda (PaymentLink oluşturulduktan sonra)
                scheduler.schedule(() -> {
                    try {
                        processPaidRequest(request);
                    } catch (StorageException e) {
                        logger.error("Otomatik payment check hatası: ", e);
                    }
                }, 60, TimeUnit.MINUTES); // 60 dk sonra çalışacak

                // create() metodunun sonunda, Notification gönderme
                if (currentUser != null) {
                    try {
                        String subject = "Ödeme linkiniz oluşturuldu";
                        String body = "Link üzerinden ödemeyi tamamlayınız.";

                        NotificationMessage message = new NotificationMessage(subject, "", body, true);
                        // Örneğin firebase notificator
                        notificatorManager.getNotificator("firebase").send(currentUser, message, null, null);
                    } catch (Exception e) {
                        logger.warn("Notification gönderilemedi: {}", e.getMessage());
                    }
                }

                return request;

            } else {
                String error = response.readEntity(String.class);
                response.close();
                logger.error("Wix error response: {}", error);
                throw new StorageException("Paylink oluşturulamadı: " + error);
            }

        } catch (Exception e) {
            logger.error("PaymentLink create error: ", e);
            throw new StorageException("Paylink oluşturma hatası: " + e.getMessage(), e);
        }

    }

    /**
     * Kullanıcının veya belirtilen userId’nin paylinklerini getir
     */
    @GET
    public Collection<PaymentRequest> list(@QueryParam("userId") Long userId) throws StorageException {
        long currentUserId = getUserId();

        // Eğer query param verilmişse -> admin / yetkili kontrol
        if (userId != null) {
            permissionsService.checkUser(currentUserId, userId);
        } else {
            userId = currentUserId;
            permissionsService.checkUser(currentUserId, userId);
        }

        Condition condition = new Condition.Equals("userid", userId);

        return storage.getObjects(PaymentRequest.class, new Request(
                new Columns.All(),
                condition,
                new Order("createdAt")));
    }

    /**
     * Paylink fiyatlarını döner
     */
    @GET
    @Path("prices")
    public Map<String, Integer> getPrices() {
        return Map.of(
                "yillikPrice", YILLIKPRICE,
                "proPrice", PROPRICE);
    }

    /**
     * Tek bir paylink getir (veritabanından)
     */
    @GET
    @Path("{id}")
    public PaymentRequest get(@PathParam("id") long id) throws StorageException {
        PaymentRequest request = storage.getObject(PaymentRequest.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", id)));

        if (request == null) {
            throw new NotFoundException("Paylink bulunamadı");
        }

        permissionsService.checkUser(getUserId(), request.getUserid());
        return request;
    }

    /**
     * Paylink status güncelle (Wix API üzerinden kontrol et)
     */

    @PUT
    @Path("{id}/status")
    public PaymentRequest updateStatus(@PathParam("id") long id) throws StorageException {
        PaymentRequest request = storage.getObject(PaymentRequest.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", id)));

        if (request == null) {
            throw new NotFoundException("Paylink bulunamadı");
        }

        permissionsService.checkUser(getUserId(), request.getUserid());

        try {
            String url = "https://www.wixapis.com/payment-links/v1/payment-links/" + request.getPaylinkId();
            Response response = client.target(url)
                    .request()
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("wix-site-id", SITE_ID)
                    .get();

            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                String resp = response.readEntity(String.class);
                response.close();

                org.json.JSONObject obj = new org.json.JSONObject(resp);
                String status = obj.getJSONObject("paymentLink").getString("status");

                request.setStatus(status);
                request.setUpdatedAt(new Date());
                storage.updateObject(request, new Request(
                        new Columns.Include("status", "updatedAt"),
                        new Condition.Equals("id", request.getId())));

                if ("PAID".equalsIgnoreCase(status) && !request.isProcessed()) {
                    processPaidRequest(request);

                }

                return request;
            } else {
                String error = response.readEntity(String.class);
                response.close();
                throw new StorageException("Durum sorgulama hatası: " + error);
            }

        } catch (Exception e) {
            throw new StorageException("Durum sorgulama hatası: " + e.getMessage(), e);
        }
    }

    // PaymentLinkResource.java'ya eklenecek yeni metod ve create değişiklikleri

    // ========================
    // 1. YENİ: Loyalty bakiye sorgulama endpoint'i
    // ========================

    @GET
    @Path("loyalty")
    public Map<String, Object> getLoyaltyBalance() throws StorageException {
        User currentUser = permissionsService.getUser(getUserId());
        if (currentUser == null) {
            throw new StorageException("Current user not found");
        }

        try {
            // Adım 1: Email ile Contact ID bul
            String contactId = getWixContactId(currentUser.getEmail());

            // Adım 2: Contact ID ile bakiye sorgula
            int balance = getWixLoyaltyBalance(contactId);

            return Map.of(
                    "points", balance,
                    "discountTL", balance / 10 // 10 puan = 1 TL
            );
        } catch (Exception e) {
            logger.warn("Loyalty bakiye alınamadı: {}", e.getMessage());
            return Map.of("points", 0, "discountTL", 0);
        }
    }

    // ========================
    // 2. YARDIMCI: Wix Contact ID sorgula
    // ========================

    private String getWixContactId(String email) throws Exception {
        org.json.JSONObject filter = new org.json.JSONObject()
                .put("query", new org.json.JSONObject()
                        .put("filter", new org.json.JSONObject()
                                .put("info.emails.email", new org.json.JSONObject()
                                        .put("$eq", email))));

        Response response = client.target("https://www.wixapis.com/contacts/v4/contacts/query")
                .request()
                .header("Authorization", "Bearer " + API_KEY)
                .header("wix-site-id", SITE_ID)
                .header("Content-Type", "application/json")
                .post(Entity.json(filter.toString()));

        String resp = response.readEntity(String.class);
        response.close();

        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            throw new Exception("Contact sorgulanamadı: " + resp);
        }

        org.json.JSONArray contacts = new org.json.JSONObject(resp).getJSONArray("contacts");
        if (contacts.length() == 0) {
            throw new Exception("Contact bulunamadı: " + email);
        }
        return contacts.getJSONObject(0).getString("id");
    }

    // ========================
    // 3. YARDIMCI: Wix Loyalty bakiye sorgula
    // ========================

    private int getWixLoyaltyBalance(String contactId) throws Exception {
        org.json.JSONObject body = new org.json.JSONObject()
                .put("query", new org.json.JSONObject()
                        .put("filter", new org.json.JSONObject()
                                .put("contactId", new org.json.JSONObject()
                                        .put("$eq", contactId))));

        Response response = client.target("https://www.wixapis.com/loyalty-accounts/v1/accounts/query")
                .request()
                .header("Authorization", "Bearer " + API_KEY)
                .header("wix-site-id", SITE_ID)
                .header("Content-Type", "application/json")
                .post(Entity.json(body.toString()));

        String resp = response.readEntity(String.class);
        response.close();

        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            throw new Exception("Loyalty sorgulanamadı: " + resp);
        }

        org.json.JSONObject data = new org.json.JSONObject(resp);
        org.json.JSONArray accounts = data.has("accounts")
                ? data.getJSONArray("accounts")
                : data.getJSONArray("loyaltyAccounts");

        if (accounts.length() == 0) {
            return 0; // Hesap yoksa 0 puan
        }
        return accounts.getJSONObject(0)
                .getJSONObject("points")
                .getInt("balance");
    }

    // ========================
    // 4. YARDIMCI: Loyalty puanını sıfırla
    // ========================
    private void resetLoyaltyPoints(String contactId) throws Exception {
        // Önce account bilgisini al (id ve revision lazım)
        org.json.JSONObject body = new org.json.JSONObject()
                .put("query", new org.json.JSONObject()
                        .put("filter", new org.json.JSONObject()
                                .put("contactId", new org.json.JSONObject()
                                        .put("$eq", contactId))));

        Response response = client.target("https://www.wixapis.com/loyalty-accounts/v1/accounts/query")
                .request()
                .header("Authorization", "Bearer " + API_KEY)
                .header("wix-site-id", SITE_ID)
                .header("Content-Type", "application/json")
                .post(Entity.json(body.toString()));

        int status = response.getStatus();
        String resp = response.readEntity(String.class);
        response.close();

        if (status != Response.Status.OK.getStatusCode()) {
            throw new Exception("Loyalty account alınamadı: " + resp);
        }

        org.json.JSONObject data = new org.json.JSONObject(resp);
        org.json.JSONArray accounts = data.has("accounts")
                ? data.getJSONArray("accounts")
                : data.getJSONArray("loyaltyAccounts");

        if (accounts.length() == 0)
            return; // hesap yoksa geç

        org.json.JSONObject account = accounts.getJSONObject(0);
        String accountId = account.getString("id");
        String revision = account.getString("revision");

        // Puanı sıfırla
        org.json.JSONObject adjustPayload = new org.json.JSONObject()
                .put("description", "Ödeme indirimi olarak kullanıldı")
                .put("revision", revision)
                .put("balance", 0);

        Response adjustResponse = client.target(
                "https://www.wixapis.com/loyalty-accounts/v1/accounts/" + accountId + "/adjust-points")
                .request()
                .header("Authorization", "Bearer " + API_KEY)
                .header("wix-site-id", SITE_ID)
                .header("Content-Type", "application/json")
                .post(Entity.json(adjustPayload.toString()));

        int adjustStatus = adjustResponse.getStatus();
        String adjustResp = adjustResponse.readEntity(String.class);
        adjustResponse.close();

        if (adjustStatus != Response.Status.OK.getStatusCode()) {
            throw new Exception("Puan sıfırlanamadı: " + adjustResp);
        }

        logger.info("Loyalty puanı sıfırlandı, accountId: {}", accountId);
    }

}
