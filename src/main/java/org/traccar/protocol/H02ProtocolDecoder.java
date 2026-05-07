/*
 * H02ProtocolDecoder.java
 *
 * Fully updated to match i-Trac GPS GPRS Protocol specification:
 *  - Section 1  : V1 Heartbeat (text)
 *  - Section 2  : Binary location ($)
 *  - Section 3.3: V4 command-reply parsing (CMD field captured)
 *  - Section 3.4: S10 / S11 / S12 replies
 *  - Section 3.5: NBR, LINK, V3, VP1, HTBT, SMS
 *  - Appendix I : vehicle_status bit-map (negative logic, 4-byte / 32-bit)
 *  - Battery    : 0-6 scale (0=dead … 6=100 %)
 *  - Alarms     : TOW, SHOCK(VIBRATION), SOS, OVERSPEED, POWER_CUT,
 *                 LOW_BATTERY, DOOR (mapped to correct bits)
 *  - Keys       : KEY_IGNITION, KEY_ARMED, KEY_DOOR, KEY_BLOCKED,
 *                 KEY_CHARGE, KEY_STATUS
 */

package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.session.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.config.Keys;
import org.traccar.helper.BcdUtil;
import org.traccar.helper.BitUtil;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Pattern;

public class H02ProtocolDecoder extends BaseProtocolDecoder {

    public H02ProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    // -------------------------------------------------------------------------
    // Binary coordinate decoder (BCD-packed)
    // -------------------------------------------------------------------------
    private static double readCoordinate(ByteBuf buf, boolean lon) {
        int degrees = BcdUtil.readInteger(buf, 2);
        if (lon) {
            degrees = degrees * 10 + (buf.getUnsignedByte(buf.readerIndex()) >> 4);
        }

        double result = 0;
        if (lon) {
            result = buf.readUnsignedByte() & 0x0f;
        }

        int length = lon ? 5 : 6;
        result = result * 10 + BcdUtil.readInteger(buf, length) * 0.0001;
        result /= 60;
        result += degrees;
        return result;
    }

    // -------------------------------------------------------------------------
    // Battery level — protocol scale: 0=dead, 1=5%, 2=20%, 3=40%,
    // 4=60%, 5=80%, 6=100%
    // -------------------------------------------------------------------------
    private Integer decodeBattery(int value) {
        return switch (value) {
            case 0 -> 0;
            case 1 -> 5;
            case 2 -> 20;
            case 3 -> 40;
            case 4 -> 60;
            case 5 -> 80;
            case 6 -> 100;
            // Some firmware variants send a direct percentage (7-100)
            // or a 0xF1-0xF6 range — keep legacy fallback.
            default -> (value >= 7 && value <= 100) ? value : null;
        };
    }

    // -------------------------------------------------------------------------
    // vehicle_status — 32-bit word (4 ASCII hex bytes), negative logic (0=active)
    //
    // Bit layout (Appendix I, counting from LSB = bit 0):
    //
    // BYTE 1 (bits 0-7) — transmitted as 1st hex byte
    // bit 0 : Tow alarm
    // bit 1 : Shock / vibration alarm
    // bit 2 : Stored waypoints
    // bit 3 : Car in oil-cut status
    // bit 4 : Main power off alarm
    // bits 5-7 : Undefined (default 1)
    //
    // BYTE 2 (bits 8-15) — transmitted as 2nd hex byte
    // bit 8 : Undefined (GPS receiver error → default 0 means error)
    // bit 9 : Shock alarm (duplicate in some firmware)
    // bit 10 : Undefined
    // bit 11 : Disconnect / using backup battery
    // bits 12-15: Undefined (default 1)
    //
    // BYTE 3 (bits 16-23) — transmitted as 3rd hex byte
    // bit 16 : Car door open alarm
    // bit 17 : Vehicle defence alarm on
    // bit 18 : ACC (ignition) — bit=0 means OFF
    // bit 19 : Undefined
    // bit 20 : Undefined
    // bit 21 : Engine status (bit=0 means engine ON)
    // bits 22-23: Undefined
    //
    // BYTE 4 (bits 24-31) — transmitted as 4th hex byte
    // bit 24 : Undefined
    // bit 25 : Thief alarm / SOS
    // bit 26 : Overspeed alarm
    // bit 27 : Unauthorized start-car alarm
    // bit 28 : Unauthorized open-door alarm
    // bit 29 : Vehicle battery low alarm
    // bits 30-31: Undefined
    // -------------------------------------------------------------------------
    private void processStatus(Position position, long status) {

        // ---- BYTE 1: Tow & Shock alarms ----
        if (!BitUtil.check(status, 0)) {
            position.addAlarm(Position.ALARM_TOW);
        }
        if (!BitUtil.check(status, 1)) {
            position.addAlarm(Position.ALARM_VIBRATION);
        }

        // ---- BYTE 1: Oil cut / fuel cut ----
        position.set(Position.KEY_BLOCKED, !BitUtil.check(status, 3));

        // ---- BYTE 1: Main power alarm ----
        if (!BitUtil.check(status, 4)) {
            position.addAlarm(Position.ALARM_POWER_CUT);
        }

        // ---- BYTE 2: Backup battery in use (main disconnected) ----
        position.set(Position.KEY_CHARGE, BitUtil.check(status, 11));

        // ---- BYTE 3: Door open alarm ----
        if (!BitUtil.check(status, 16)) {
            position.addAlarm(Position.ALARM_DOOR);
        }

        // ---- BYTE 3: Defence / armed ----
        position.set(Position.KEY_ARMED, !BitUtil.check(status, 17));

        // ---- BYTE 3: ACC / Ignition (bit=0 → ACC OFF) ----
        position.set(Position.KEY_IGNITION, !BitUtil.check(status, 18));

        // ---- BYTE 3: Engine (bit=0 → engine running) ----
        // Stored as motion proxy; callers may use KEY_MOTION
        position.set(Position.KEY_MOTION, !BitUtil.check(status, 21));

        // ---- BYTE 4: SOS / Thief alarm ----
        if (!BitUtil.check(status, 25)) {
            position.addAlarm(Position.ALARM_SOS);
        }

        // ---- BYTE 4: Overspeed alarm ----
        if (!BitUtil.check(status, 26)) {
            position.addAlarm(Position.ALARM_OVERSPEED);
        }

        // ---- BYTE 4: Low vehicle battery alarm ----
        if (!BitUtil.check(status, 29)) {
            position.addAlarm(Position.ALARM_LOW_BATTERY);
        }

        // Store raw status word for debugging / forwarding
        position.set(Position.KEY_STATUS, status);
    }

    // -------------------------------------------------------------------------
    // Response sender
    // -------------------------------------------------------------------------
    private void sendResponse(Channel channel, SocketAddress remoteAddress,
            String id, String type) {
        if (channel == null || id == null) {
            return;
        }
        DateFormat dateFormat = new SimpleDateFormat(
                type.equals("R12") ? "HHmmss" : "yyyyMMddHHmmss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String time = dateFormat.format(new Date());

        String response;
        if (type.equals("R12")) {
            response = String.format("*HQ,%s,%s,%s#", id, type, time);
        } else {
            response = String.format("*HQ,%s,V4,%s,%s#", id, type, time);
        }
        channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
    }

    // =========================================================================
    // BINARY decoder (marker = '$')
    // Section 2 of the protocol spec
    //
    // Frame layout (45 bytes total):
    // [00] $ (marker)
    // [01-05] GPS Tracker ID (5 bytes BCD → 10 hex digits)
    // OR [01-08] 8-byte long-ID (longId = frame is 42 bytes after '$')
    // [06-08] Time HH MM SS (BCD)
    // [09-0B] Date DD MM YY (BCD)
    // [0C-0F] Latitude (BCD, 4 bytes)
    // [10] Backup battery value (0-6)
    // [11-15] Longitude (BCD, 5 bytes)
    // [16] Flags nibble (bit1=valid, bit2=N/S, bit3=E/W)
    // [17-19] Speed (BCD 3 digits, knots)
    // [1A-1C] Course / direction
    // [19-1C] vehicle_status (uint32, big-endian)
    // [1D-25] Undefined
    // [26] MCC high byte
    // [27] MNC
    // [28-29] LAC
    // [2A-2B] Cell ID
    // [2C] Record number
    // =========================================================================
    private Position decodeBinary(ByteBuf buf, Channel channel,
            SocketAddress remoteAddress) {
        Position position = new Position(getProtocolName());

        // longId: if readable bytes == 42 the device ID is 8 bytes (15-digit IMEI)
        boolean longId = buf.readableBytes() == 42;

        buf.readByte(); // '$' marker

        String id;
        if (longId) {
            id = ByteBufUtil.hexDump(buf.readSlice(8)).substring(0, 15);
        } else {
            id = ByteBufUtil.hexDump(buf.readSlice(5));
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }
        position.setDeviceId(deviceSession.getDeviceId());

        // Time
        DateBuilder dateBuilder = new DateBuilder()
                .setHour(BcdUtil.readInteger(buf, 2))
                .setMinute(BcdUtil.readInteger(buf, 2))
                .setSecond(BcdUtil.readInteger(buf, 2))
                .setDay(BcdUtil.readInteger(buf, 2))
                .setMonth(BcdUtil.readInteger(buf, 2))
                .setYear(BcdUtil.readInteger(buf, 2));
        position.setTime(dateBuilder.getDate());

        // Latitude (BCD)
        double latitude = readCoordinate(buf, false);

        // Backup battery (0-6 scale)
        position.set(Position.KEY_BATTERY_LEVEL, decodeBattery(buf.readUnsignedByte()));

        // Longitude (BCD)
        double longitude = readCoordinate(buf, true);

        // Validity flags
        int flags = buf.readUnsignedByte() & 0x0f;
        position.setValid((flags & 0x02) != 0);
        if ((flags & 0x04) == 0)
            latitude = -latitude;
        if ((flags & 0x08) == 0)
            longitude = -longitude;

        position.setLatitude(latitude);
        position.setLongitude(longitude);

        // Speed (knots — NM/h as per spec)
        position.setSpeed(BcdUtil.readInteger(buf, 3));

        // Course
        position.setCourse((buf.readUnsignedByte() & 0x0f) * 100.0
                + BcdUtil.readInteger(buf, 2));

        // vehicle_status (4 bytes, big-endian unsigned int)
        processStatus(position, buf.readUnsignedInt());

        // Skip undefined bytes [1D-25]
        buf.skipBytes(9);

        // Network info — present in binary frame from spec example
        if (buf.readableBytes() >= 7) {
            int mcc = buf.readUnsignedShort();
            int mnc = buf.readUnsignedByte();
            int lac = buf.readUnsignedShort();
            int cellId = buf.readUnsignedShort();

            Network network = new Network();
            network.addCellTower(CellTower.from(mcc, mnc, lac, cellId));
            position.setNetwork(network);
        }

        // Record number (last byte) — store as index
        if (buf.readableBytes() >= 1) {
            position.set(Position.KEY_INDEX, buf.readUnsignedByte());
        }

        // Acknowledge
        if (getConfig().getBoolean(Keys.PROTOCOL_ACK.withPrefix(getProtocolName()))) {
            sendResponse(channel, remoteAddress, id, "R12");
        }

        return position;
    }

    // =========================================================================
    // TEXT PATTERNS
    // =========================================================================

    /**
     * Main pattern — covers V1 (heartbeat), V2 (location), V4 (cmd reply),
     * and unnamed default frames.
     *
     * Format (Section 1 / 3.3):
     * *XX,IMEI,TYPE,[CMD,]HHMMSS,S,lat,D,lon,G,speed,dir,DDMMYY,status[,extras]#
     */
    private static final Pattern PATTERN = new PatternBuilder()
            .text("*")
            .expression("..,") // manufacturer
            .number("(d+)?,") // imei
            .groupBegin()
            .text("V4,")
            .expression("(.*),") // V4 response field / CMD echo
            .or()
            .expression("(V[^,]*),") // type: V1 / V2 / Vx
            .groupEnd()
            .number("(?:(dd)(dd)(dd))?,") // time (hhmmss)
            .groupBegin()
            .expression("([ABV])?,") // validity A/V
            .or()
            .number("(d+),") // coding scheme
            .groupEnd()
            .groupBegin()
            .number("-(d+)-(d+.d+),([NS]),") // latitude (variant 1)
            .or()
            .number("(d+)(dd.d+),([NS]),") // latitude (variant 2)
            .or()
            .number("(d+)(dd)(d{4}),([NS]),") // latitude (variant 3 — BCD-like)
            .groupEnd()
            .groupBegin()
            .number("-(d+)-(d+.d+),([EW]),") // longitude (variant 1)
            .or()
            .number("(d+)(dd.d+),([EW]),") // longitude (variant 2)
            .or()
            .number("(d+)(dd)(d{4}),([EW]),") // longitude (variant 3)
            .groupEnd()
            .number(" *(d+.?d*),") // speed (knots)
            .number("(d+.?d*)?,") // course
            .number("(?:d+,)?") // battery (text frame, ignored — covered separately)
            .number("(?:(dd)(dd)(dd))?") // date (ddmmyy)
            .groupBegin()
            .expression(",[^,]*,")
            .expression("[^,]*,")
            .expression("[^,]*") // sim info
            .groupEnd("?")
            .groupBegin()
            .number(",(x{8})") // vehicle_status (8 hex chars = 4 bytes)
            .groupBegin()
            .number(",(d+),") // odometer
            .number("(-?d+),") // temperature
            .number("(d+.d+),") // fuel level
            .number("(-?d+),") // altitude
            .number("(x+),") // lac
            .number("(x+)") // cell id
            .or()
            .text(",")
            .expression("(.*)") // extra data (I/O values)
            .or()
            .groupEnd()
            .or()
            .groupEnd()
            .text("#")
            .compile();

    /** NBR — cell-tower based location (no GPS fix, LBS only) */
    private static final Pattern PATTERN_NBR = new PatternBuilder()
            .text("*")
            .expression("..,")
            .number("(d+),") // imei
            .text("NBR,")
            .number("(dd)(dd)(dd),") // time
            .number("(d+),") // mcc
            .number("(d+),") // mnc
            .number("d+,") // gsm delay time
            .number("d+,") // cell count
            .number("((?:d+,d+,-?d+,)+)") // cell list
            .number("(dd)(dd)(dd),") // date
            .number("(x{8})") // status
            .any()
            .compile();

    /** LINK — wearable / step-counter status frame */
    private static final Pattern PATTERN_LINK = new PatternBuilder()
            .text("*")
            .expression("..,")
            .number("(d+),")
            .text("LINK,")
            .number("(dd)(dd)(dd),") // time
            .number("(d+),") // rssi
            .number("(d+),") // satellites
            .number("(d+),") // battery level
            .number("(d+),") // steps
            .number("(d+),") // turnovers
            .number("(dd)(dd)(dd),") // date
            .number("(x{8})") // status
            .any()
            .compile();

    /** V3 — cell-tower report with battery */
    private static final Pattern PATTERN_V3 = new PatternBuilder()
            .text("*")
            .expression("..,")
            .number("(d+),")
            .text("V3,")
            .number("(dd)(dd)(dd),") // time
            .number("(ddd)") // mcc
            .number("(d+),") // mnc
            .number("(d+),") // cell count
            .expression("(.*),") // cell info
            .number("(x{4}),") // battery (hex)
            .number("d+,") // reboot info
            .text("X,")
            .number("(dd)(dd)(dd),") // date
            .number("(x{8})") // status
            .text("#").optional()
            .compile();

    /** VP1 — HQ-branded location or LBS frame */
    private static final Pattern PATTERN_VP1 = new PatternBuilder()
            .text("*hq,")
            .number("(d{15}),")
            .text("VP1,")
            .groupBegin()
            .text("V,")
            .number("(d+),") // mcc
            .number("(d+),") // mnc
            .expression("([^#]+)") // cells
            .or()
            .expression("[AB],") // validity
            .number("(d+)(dd.d+),") // latitude
            .expression("([NS]),")
            .number("(d+)(dd.d+),") // longitude
            .expression("([EW]),")
            .number("(d+.d+),") // speed
            .number("(d+.d+),") // course
            .number("(dd)(dd)(dd)") // date
            .groupEnd()
            .any()
            .compile();

    /** HTBT / V0 — heartbeat with battery */
    private static final Pattern PATTERN_HTBT = new PatternBuilder()
            .text("*HQ,")
            .number("(d{15}),")
            .text("HTBT,")
            .number("(d+)") // battery level
            .any()
            .compile();

    /** SMS — result string from device */
    private static final Pattern PATTERN_SMS = new PatternBuilder()
            .text("*HQ,")
            .number("(d+),")
            .text("SMS,")
            .expression("(.+)")
            .text("#")
            .compile();

    // =========================================================================
    // TEXT decoders
    // =========================================================================

    /**
     * Decodes V1 (heartbeat), V2 (location request reply), V4 (command reply),
     * and any other text-format frame that matches PATTERN.
     */
    private Position decodeText(String sentence, Channel channel,
            SocketAddress remoteAddress) {
        Parser parser = new Parser(PATTERN, sentence);
        if (!parser.matches()) {
            return null;
        }

        String id = parser.next();
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        // V4 command-echo field (present when device replies to a server command)
        if (parser.hasNext()) {
            String cmdEcho = parser.next();
            if (cmdEcho != null && !cmdEcho.isEmpty()) {
                position.set(Position.KEY_RESULT, cmdEcho);
            }
        }

        // Type field (V1, V2, …); V1 needs ACK back
        String type = null;
        if (parser.hasNext()) {
            type = parser.next();
        }
        if ("V1".equals(type)) {
            sendResponse(channel, remoteAddress, id, "V1");
        } else if (getConfig().getBoolean(Keys.PROTOCOL_ACK.withPrefix(getProtocolName()))) {
            sendResponse(channel, remoteAddress, id, "R12");
        }

        DateBuilder dateBuilder = new DateBuilder();
        if (parser.hasNext(3)) {
            dateBuilder.setTime(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
        }

        // Validity
        if (parser.hasNext()) {
            position.setValid(parser.next().equals("A"));
        }
        if (parser.hasNext()) {
            parser.nextInt(); // coding scheme — implies valid
            position.setValid(true);
        }

        // Latitude (three possible variants)
        if (parser.hasNext(3)) {
            position.setLatitude(parser.nextCoordinate());
        }
        if (parser.hasNext(3)) {
            position.setLatitude(parser.nextCoordinate());
        }
        if (parser.hasNext(4)) {
            position.setLatitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_MIN_MIN_HEM));
        }

        // Longitude (three possible variants)
        if (parser.hasNext(3)) {
            position.setLongitude(parser.nextCoordinate());
        }
        if (parser.hasNext(3)) {
            position.setLongitude(parser.nextCoordinate());
        }
        if (parser.hasNext(4)) {
            position.setLongitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_MIN_MIN_HEM));
        }

        // Speed (knots — protocol specifies NM/h)
        position.setSpeed(parser.nextDouble(0));
        position.setCourse(parser.nextDouble(0));

        // Date
        if (parser.hasNext(3)) {
            dateBuilder.setDateReverse(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
            position.setTime(dateBuilder.getDate());
        } else {
            position.setTime(new Date());
        }

        // vehicle_status
        if (parser.hasNext()) {
            processStatus(position, parser.nextLong(16, 0));
        }

        // Extended fields: odometer, temperature, fuel, altitude, lac, cid
        if (parser.hasNext(6)) {
            position.set(Position.KEY_ODOMETER, parser.nextInt(0)); // meters
            position.set(Position.PREFIX_TEMP + 1, parser.nextInt(0)); // °C
            position.set(Position.KEY_FUEL_LEVEL, parser.nextDouble(0)); // litres
            position.setAltitude(parser.nextInt(0));

            int lac = parser.nextHexInt(0);
            int cid = parser.nextHexInt(0);
            Network network = new Network();
            network.addCellTower(CellTower.fromLacCid(getConfig(), lac, cid));
            position.setNetwork(network);
        }

        // Generic I/O string (io1, io2, …)
        if (parser.hasNext()) {
            String[] values = parser.next().split(",");
            for (int i = 0; i < values.length; i++) {
                position.set(Position.PREFIX_IO + (i + 1), values[i].trim());
            }
        }

        return position;
    }

    /** NBR — LBS-only position (multiple cell towers, no GPS) */
    private Position decodeLbs(String sentence, Channel channel,
            SocketAddress remoteAddress) {
        Parser parser = new Parser(PATTERN_NBR, sentence);
        if (!parser.matches()) {
            return null;
        }

        String id = parser.next();
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }

        sendResponse(channel, remoteAddress, id, "NBR");

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        DateBuilder dateBuilder = new DateBuilder()
                .setTime(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));

        int mcc = parser.nextInt(0);
        int mnc = parser.nextInt(0);

        Network network = new Network();
        String[] cells = parser.next().split(",");
        for (int i = 0; i < cells.length / 3; i++) {
            network.addCellTower(CellTower.from(
                    mcc, mnc,
                    Integer.parseInt(cells[i * 3]),
                    Integer.parseInt(cells[i * 3 + 1]),
                    Integer.parseInt(cells[i * 3 + 2])));
        }
        position.setNetwork(network);

        dateBuilder.setDateReverse(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
        getLastLocation(position, dateBuilder.getDate());

        processStatus(position, parser.nextLong(16, 0));

        return position;
    }

    /** LINK — wearable / fitness data frame */
    private Position decodeLink(String sentence, Channel channel,
            SocketAddress remoteAddress) {
        Parser parser = new Parser(PATTERN_LINK, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        DateBuilder dateBuilder = new DateBuilder()
                .setTime(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));

        position.set(Position.KEY_RSSI, parser.nextInt());
        position.set(Position.KEY_SATELLITES, parser.nextInt());
        position.set(Position.KEY_BATTERY_LEVEL, parser.nextInt());
        position.set(Position.KEY_STEPS, parser.nextInt());
        position.set("turnovers", parser.nextInt());

        dateBuilder.setDateReverse(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
        getLastLocation(position, dateBuilder.getDate());

        processStatus(position, parser.nextLong(16, 0));

        return position;
    }

    /** V3 — cell-tower report frame with hex battery value */
    private Position decodeV3(String sentence, Channel channel,
            SocketAddress remoteAddress) {
        Parser parser = new Parser(PATTERN_V3, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        DateBuilder dateBuilder = new DateBuilder()
                .setTime(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));

        int mcc = parser.nextInt();
        int mnc = parser.nextInt();
        int count = parser.nextInt();

        Network network = new Network();
        String[] values = parser.next().split(",");
        for (int i = 0; i < count; i++) {
            network.addCellTower(CellTower.from(
                    mcc, mnc,
                    Integer.parseInt(values[i * 4]),
                    Integer.parseInt(values[i * 4 + 1])));
        }
        position.setNetwork(network);

        // Battery voltage (hex, 4 chars → divide by some factor if needed)
        position.set(Position.KEY_BATTERY, parser.nextHexInt());

        dateBuilder.setDateReverse(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
        getLastLocation(position, dateBuilder.getDate());

        processStatus(position, parser.nextLong(16, 0));

        return position;
    }

    /** SMS — device sends back a text result */
    private Position decodeSms(String sentence, Channel channel,
            SocketAddress remoteAddress) {
        Parser parser = new Parser(PATTERN_SMS, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        getLastLocation(position, null);
        position.set(Position.KEY_RESULT, parser.next());

        return position;
    }

    /** VP1 — HQ-branded frame (LBS variant or GPS variant) */
    private Position decodeVp1(String sentence, Channel channel,
            SocketAddress remoteAddress) {
        Parser parser = new Parser(PATTERN_VP1, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (parser.hasNext(3)) {
            // LBS variant
            getLastLocation(position, null);

            int mcc = parser.nextInt();
            int mnc = parser.nextInt();

            Network network = new Network();
            for (String cell : parser.next().split("Y")) {
                String[] v = cell.split(",");
                network.addCellTower(CellTower.from(
                        mcc, mnc,
                        Integer.parseInt(v[0]),
                        Integer.parseInt(v[1]),
                        Integer.parseInt(v[2])));
            }
            position.setNetwork(network);

        } else {
            // GPS variant
            position.setValid(true);
            position.setLatitude(parser.nextCoordinate());
            position.setLongitude(parser.nextCoordinate());
            position.setSpeed(parser.nextDouble());
            position.setCourse(parser.nextDouble());
            position.setTime(new DateBuilder()
                    .setDateReverse(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0))
                    .getDate());
        }

        return position;
    }

    /**
     * HTBT / V0 — heartbeat with battery level.
     * Device sends this automatically every 5 minutes (Section 3.5).
     */
    private Position decodeHeartbeat(String sentence, Channel channel,
            SocketAddress remoteAddress) {
        Parser parser = new Parser(PATTERN_HTBT, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        getLastLocation(position, null);
        position.set(Position.KEY_BATTERY_LEVEL, parser.nextInt());

        return position;
    }

    // =========================================================================
    // Entry point
    // =========================================================================
    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress,
            Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;
        String marker = buf.toString(0, 1, StandardCharsets.US_ASCII);

        switch (marker) {

            // ------------------------------------------------------------------
            // TEXT frames (* marker)
            // ------------------------------------------------------------------
            case "*" -> {
                String sentence = buf.toString(StandardCharsets.US_ASCII).trim();

                // Extract message type (3rd comma-delimited token)
                int typeStart = sentence.indexOf(',', sentence.indexOf(',') + 1) + 1;
                int typeEnd = sentence.indexOf(',', typeStart);
                if (typeEnd < 0) {
                    typeEnd = sentence.indexOf('#', typeStart);
                }
                if (typeEnd <= 0) {
                    return null;
                }

                String type = sentence.substring(typeStart, typeEnd);

                return switch (type) {

                    // V0 / HTBT — heartbeat frames; echo back immediately
                    case "V0", "HTBT" -> {
                        if (channel != null) {
                            // Echo: strip everything after type, append '#'
                            String response = sentence.substring(0, typeEnd) + "#";
                            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
                        }
                        yield decodeHeartbeat(sentence, channel, remoteAddress);
                    }

                    // NBR — LBS frame
                    case "NBR" -> decodeLbs(sentence, channel, remoteAddress);

                    // LINK — wearable / step-counter
                    case "LINK" -> decodeLink(sentence, channel, remoteAddress);

                    // V3 — cell report with hex battery
                    case "V3" -> decodeV3(sentence, channel, remoteAddress);

                    // VP1 — *hq, branded frame
                    case "VP1" -> decodeVp1(sentence, channel, remoteAddress);

                    // SMS — device text reply
                    case "SMS" -> decodeSms(sentence, channel, remoteAddress);

                    /*
                     * V1 (heartbeat), V2 (location), V4 (command reply),
                     * S10/S11/S12 replies (arrive as V4 frames) — all fall
                     * through to the universal text decoder.
                     * The V4 CMD echo field is stored in KEY_RESULT.
                     */
                    default -> decodeText(sentence, channel, remoteAddress);
                };
            }

            // ------------------------------------------------------------------
            // BINARY frames ($ marker) — Section 2
            // ------------------------------------------------------------------
            case "$" -> {
                return decodeBinary(buf, channel, remoteAddress);
            }

            // ------------------------------------------------------------------
            // Unknown marker — ignore
            // ------------------------------------------------------------------
            default -> {
                return null;
            }
        }
    }
}