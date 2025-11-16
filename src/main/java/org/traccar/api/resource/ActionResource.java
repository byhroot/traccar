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
            @QueryParam("userId") Long userId) throws StorageException {

        permissionsService.checkAdmin(getUserId());

        Condition condition = new Condition.Between("actiontime",from,to);

        if (userId != null) {
            condition = new Condition.And(
                condition, 
                new Condition.Equals("userid", userId)
            );
        }

        return storage.getObjects(Action.class, new Request(
                new Columns.All(),
                condition,
                new Order("actiontime")));
    }
}
