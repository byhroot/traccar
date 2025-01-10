package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.model.Userlogsapi;
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

@Path("userlogs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserlogsResource extends BaseResource {

    @GET
    public Collection<Userlogsapi> get(
            @QueryParam("from") Date from, 
            @QueryParam("to") Date to, 
            @QueryParam("userId") Long userId) throws StorageException {

        permissionsService.checkAdmin(getUserId());

        Condition condition = new Condition.Between("timestamp", "from", from, "to", to);

        if (userId != null) {
            condition = new Condition.And(
                condition, 
                new Condition.Equals("userId", userId)
            );
        }

        return storage.getObjects(Userlogsapi.class, new Request(
                new Columns.All(),
                condition,
                new Order("timestamp")));
    }
}
