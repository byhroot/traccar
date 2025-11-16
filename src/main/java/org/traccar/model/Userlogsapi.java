package org.traccar.model;

import org.traccar.storage.StorageName;

import java.util.Date;


@StorageName("tc_userlogs")
public class Userlogsapi extends ExtendedModel {

    private Date timestamp;

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    private long userId;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }
    
    private String action;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
    

}
