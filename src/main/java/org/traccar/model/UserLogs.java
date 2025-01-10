package org.traccar.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;
import org.traccar.storage.StorageName;

import java.util.Date;

@StorageName("tc_userlogs")
public class UserLogs {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserLogs.class); 

    private long userid;  // Kullanıcı ID'si
    private String action;  // Kullanıcının yaptığı eylem
    private Date timestamp;  // İşlem tarihi
    private Storage storage;  // Storage örneği (constructor)

    // Constructor, Storage nesnesini alır
    public UserLogs(Storage storage) {
        this.storage = storage;
    }

    // Getter ve Setter metodları
    public long getUserid() {
        return userid;
    }

    public void setUserid(long userid) {
        this.userid = userid;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    // Veritabanına kaydetme işlemi
    public void saveToDatabase(long userId, String action) {
        this.userid = userId;
        this.action = action;
        this.timestamp = new Date();  // Geçerli tarihi alır

        try {
            // Veritabanına kaydetme işlemi
            if (storage != null) {
                storage.addObject(this, new Request(new Columns.Exclude("id")));
            } else {
                LOGGER.error("Storage is not initialized!");
            }
        } catch (StorageException e) {
            LOGGER.error("Hata veritabanına kaydederken oluştu: ", e);
        }
    }
}
