package org.traccar.handler;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Date;

public class IdleProcessingHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdleProcessingHandler.class);
    private static final String DEVICE_ATTR_KEY = "idleMinDuration";

    private final CacheManager cacheManager;
    private final boolean active;

    @Inject
    public IdleProcessingHandler(Config config, CacheManager cacheManager) {
        this.cacheManager = cacheManager;

        boolean tmpActive = false;
        try {
            tmpActive = config.getBoolean(Keys.PROCESSING_EVENT_IDLE);
        } catch (Exception e) {
            LOGGER.warn("IdleProcessingHandler: config okunamadı", e);
        }
        this.active = tmpActive;
    }

    private long resolveIdleThreshold(long deviceId) {
        try {
            Device device = cacheManager.getObject(Device.class, deviceId);
            if (device != null && device.getAttributes().containsKey(DEVICE_ATTR_KEY)) {
                Object val = device.getAttributes().get(DEVICE_ATTR_KEY);
                long minutes = -1;
                if (val instanceof Number) {
                    minutes = ((Number) val).longValue();
                } else if (val instanceof String) {
                    minutes = Long.parseLong((String) val);
                }
                // key var ama 0 → kapalı
                if (minutes == 0) {
                    return 0;
                }
                // key var ve > 0 → kullan
                if (minutes > 0) {
                    LOGGER.debug("Araç {} için özel idleMinDuration: {} dakika", deviceId, minutes);
                    return minutes * 60 * 1000L;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Araç {} için idleMinDuration okunamadı", deviceId, e);
        }
        // key hiç tanımlı değil → varsayılan 20 dakika //0 yaparsak varsayılan süre
        // olmaz, sadece araç bazında süre tanımlanırsa çalışır olur
        return 20 * 60 * 1000L;
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        if (!active) {
            callback.processed(false);
            return;
        }

        if (!PositionUtil.isLatest(cacheManager, position)) {
            callback.processed(false);
            return;
        }

        long deviceId = position.getDeviceId();
        Position last = cacheManager.getPosition(deviceId);

        boolean ignition = position.getBoolean(Position.KEY_IGNITION);
        boolean motion = position.getBoolean(Position.KEY_MOTION);
        double speed = position.getSpeed();
        Date now = new Date();

        if (!ignition || motion) {
            callback.processed(false);
            return;
        }

        if (last == null) {
            callback.processed(false);
            return;
        }

        boolean lastIgnition = last.getBoolean(Position.KEY_IGNITION);
        boolean lastMotion = last.getBoolean(Position.KEY_MOTION);
        double lastSpeed = last.getSpeed();

        boolean resetIdle = (!lastIgnition && ignition)
                || (lastMotion && !motion)
                || (lastSpeed > 1.0 && speed <= 1.0);

        if (resetIdle) {
            position.getAttributes().put("idleStartTime", String.valueOf(now.getTime()));
            position.getAttributes().put("idleAlarmStatus", false);
            callback.processed(false);
            return;
        }

        // idleStartTime oku
        Date idleStart = null;
        if (last.getAttributes().containsKey("idleStartTime")) {
            Object v = last.getAttributes().get("idleStartTime");
            if (v instanceof Number) {
                idleStart = new Date(((Number) v).longValue());
            } else if (v instanceof String) {
                try {
                    idleStart = new Date(Long.parseLong((String) v));
                } catch (NumberFormatException e) {
                    LOGGER.warn("idleStartTime parse hatası, device={}", deviceId);
                }
            }
        }

        if (idleStart == null) {
            position.getAttributes().put("idleStartTime", String.valueOf(now.getTime()));
            position.getAttributes().put("idleAlarmStatus", false);
            callback.processed(false);
            return;
        }

        // idleStartTime taşı
        position.getAttributes().put("idleStartTime", last.getAttributes().get("idleStartTime"));

        // alarmStatus oku
        boolean alarmStatus = false;
        if (last.getAttributes().containsKey("idleAlarmStatus")) {
            Object a = last.getAttributes().get("idleAlarmStatus");
            if (a instanceof Boolean) {
                alarmStatus = (Boolean) a;
            } else if (a instanceof String) {
                alarmStatus = Boolean.parseBoolean((String) a);
            }
        }

        // threshold kontrolü — alarm kararı burada veriliyor
        // DatabaseHandler henüz çalışmadı, bu position'a yazılacak
        long idleThreshold = resolveIdleThreshold(deviceId);
        long diff = now.getTime() - idleStart.getTime();

        if (idleThreshold > 0 && !alarmStatus && diff >= idleThreshold) {
            position.getAttributes().put("idleAlarmStatus", true);
            position.getAttributes().put("alarm", Position.ALARM_IDLE);
        } else {
            position.getAttributes().put("idleAlarmStatus", alarmStatus);
        }

        callback.processed(false);
    }
}