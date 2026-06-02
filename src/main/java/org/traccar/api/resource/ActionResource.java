package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.model.Action;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Collection;
import java.util.Date;

@Path("actions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ActionResource extends BaseResource {

    @GET
    public Collection<Action> get(
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("userId") Long userId,
            @QueryParam("actionType") String actionType,
            @QueryParam("objectType") String objectType,
            @QueryParam("objectId") Long objectId,
            @QueryParam("address") String address) throws StorageException {

        permissionsService.checkAdmin(getUserId());

        // Zorunlu: tarih aralığı
        Condition condition = new Condition.Between("actiontime", from, to);

        if (userId != null) {
            condition = new Condition.And(condition, new Condition.Equals("userid", userId));
        }
        if (actionType != null && !actionType.isBlank()) {
            condition = new Condition.And(condition, new Condition.Equals("actiontype", actionType));
        }
        if (objectType != null && !objectType.isBlank()) {
            condition = new Condition.And(condition, new Condition.Equals("objecttype", objectType));
        }
        if (objectId != null) {
            condition = new Condition.And(condition, new Condition.Equals("objectid", objectId));
        }
        if (address != null && !address.isBlank()) {
            condition = new Condition.And(condition, new Condition.Equals("address", address));
        }

        return storage.getObjects(Action.class, new Request(
                new Columns.All(),
                condition,
                new Order("actiontime")));
    }
}