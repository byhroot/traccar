/*
 * Copyright 2017 - 2024 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 - 2018 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.notificators;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.database.StatisticsManager;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.sms.SmsManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.storage.query.Columns;


@Singleton
public class NotificatorSms extends Notificator {

    private final SmsManager smsManager;
    private final StatisticsManager statisticsManager;
    private final Storage storage;

    @Inject
    public NotificatorSms(
            SmsManager smsManager, NotificationFormatter notificationFormatter, StatisticsManager statisticsManager, Storage storage) {
        super(notificationFormatter, "short");
        this.smsManager = smsManager;
        this.statisticsManager = statisticsManager;
        this.storage = storage;

    }

    @Override
    public void send(User user, NotificationMessage message, Event event, Position position) throws MessageException {
        if (user.getPhone() != null) {
            // SMS limit kontrolü
            if (user.hasAttribute("smsLimit")) {
                int smsLimit = user.getInteger("smsLimit");  // smsLimit doğrudan sayı olarak alınıyor
                if (smsLimit > 0) {
                                    // Mesajın uzunluğunu kontrol et
                    int messageLength = message.getBody().length();
                    // 150 karakter için 1 SMS hesapla
                    int smsNeeded = (int) Math.ceil(messageLength / 150.0);
                    
                    if (smsLimit > smsNeeded) {
                        statisticsManager.registerSms();
                        smsManager.sendMessage(user.getPhone(), message.getBody(), false);

                    // SMS limitini güncelleme
                    try {
                        user.set("smsLimit", smsLimit - smsNeeded);

                        // Güncellenmiş kullanıcıyı veritabanına kaydet
                        storage.updateObject(user, new Request(
                                new Columns.Include("attributes"), // attributes içinde güncelleme yapıyoruz
                                new Condition.Equals("id", user.getId())));
                    } catch (StorageException e) {
                        throw new MessageException("Hata Oluştu - Error updating SMS limit: " + e.getMessage());
                    }
                } else {
                
                    throw new MessageException("SMS Limit Yetersiz - SMS limit exceeded");
                }
            } else {
                
                throw new MessageException("SMS Limit Yetersiz - SMS limit exceeded");
            }
        } else {
            throw new MessageException("SMS Aktif Değil - SMS limit attribute not found");
        }
    }
    }

}
