/*
 * Copyright 2024 TakipOn Mobil Takip Sistemleri
 */
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.session.DeviceSession;

import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.BitUtil;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;

public class Takiponr12ProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(Takiponr12ProtocolDecoder.class);

    public Takiponr12ProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    public static final int MSG_LOGIN = 0x01; //// SEEWORLD LOGİN
    public static final int MSG_GPS_LBS_1 = 0x12; //// SEEWORLD LOCATİON
    public static final int MSG_STATUS = 0x13; //// SEEWORLD STATUS İNFORMATİON
    public static final int MSG_STRING = 0x15; //// SEEWORLD STRİNG İNFORMATİON
    public static final int MSG_ALARM_STATUS = 0x16; //// SEEWORLD ALARM LBS
    public static final int MSG_LBS_EXTEND = 0x18; //// SEEWORLD NETWORK MESSAGE
    public static final int MSG_COMMAND_0 = 0x80; //// SEEWORLD COMMAND
    public static final int MSG_INFO = 0x94; //// SEEWORLD ICCID NUMBER

    private enum Variant {
        VXT01,
        WANWAY_S20,
        SR411_MINI,
        GT06E_CARD,
        BENWAY,
        S5,
        SPACE10X,
        STANDARD,
        OBD6,
        WETRUST,
        JC400,
    }

    private Variant variant;

    private static boolean isSupported(int type) {
        return hasGps(type) || hasLbs(type) || hasStatus(type);
    }

    private static boolean hasGps(int type) {
        switch (type) {
            case MSG_GPS_LBS_1:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasLbs(int type) {
        switch (type) {
            case MSG_GPS_LBS_1:
            case MSG_ALARM_STATUS:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasStatus(int type) {
        switch (type) {
            case MSG_STATUS:
            case MSG_ALARM_STATUS:
                return true;
            default:
                return false;
        }
    }

    private void sendResponse(Channel channel, boolean extended, int type, int index, ByteBuf content) {
        if (channel != null) {
            ByteBuf response = Unpooled.buffer();
            int length = 5 + (content != null ? content.readableBytes() : 0);
            if (extended) {
                response.writeShort(0x7979);
                response.writeShort(length);
            } else {
                response.writeShort(0x7878);
                response.writeByte(length);
            }
            response.writeByte(type);
            if (content != null) {
                response.writeBytes(content);
                content.release();
            }
            response.writeShort(index);
            response.writeShort(Checksum.crc16(Checksum.CRC16_X25,
                    response.nioBuffer(2, response.writerIndex() - 2)));
            response.writeByte('\r');
            response.writeByte('\n');
            channel.writeAndFlush(new NetworkMessage(response, channel.remoteAddress()));
        }
    }

    public static boolean decodeGps(Position position, ByteBuf buf, boolean hasLength, TimeZone timezone) {
        return decodeGps(position, buf, hasLength, true, true, false, timezone);
    }

    public static boolean decodeGps(
            Position position, ByteBuf buf, boolean hasLength, boolean hasSatellites,
            boolean hasSpeed, boolean longSpeed, TimeZone timezone) {

        DateBuilder dateBuilder = new DateBuilder(timezone)
                .setDate(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte())
                .setTime(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte());
        position.setTime(dateBuilder.getDate());

        if (hasLength && buf.readUnsignedByte() == 0) {
            return false;
        }

        if (hasSatellites) {
            position.set(Position.KEY_SATELLITES, BitUtil.to(buf.readUnsignedByte(), 4));
        }

        double latitude = buf.readUnsignedInt() / 60.0 / 30000.0;
        double longitude = buf.readUnsignedInt() / 60.0 / 30000.0;

        if (hasSpeed) {
            position.setSpeed(UnitsConverter.knotsFromKph(
                    longSpeed ? buf.readUnsignedShort() : buf.readUnsignedByte()));

        }

        int flags = buf.readUnsignedShort();
        position.setCourse(BitUtil.to(flags, 10));
        position.setValid(BitUtil.check(flags, 12));

        if (!BitUtil.check(flags, 10)) {
            latitude = -latitude;
        }
        if (BitUtil.check(flags, 11)) {
            longitude = -longitude;
        }

        position.setLatitude(latitude);
        position.setLongitude(longitude);

        position.set("gpsDifferential", BitUtil.check(flags, 13)); // GPS real-time/differential positioning

        if (BitUtil.check(flags, 14)) {
            position.set(Position.KEY_IGNITION, BitUtil.check(flags, 14));
            // LOGGER.info("Kontak Durumu GPS Paketinden alındı");

        }
        return true;
    }

    private boolean decodeLbs(Position position, ByteBuf buf, int type, boolean hasLength) {

        int length = 0;
        if (hasLength) {
            length = buf.readUnsignedByte();
            if (length == 0) {
                boolean zeroedData = true;
                for (int i = buf.readerIndex() + 9; i < buf.readerIndex() + 45 && i < buf.writerIndex(); i++) {
                    if (buf.getByte(i) != 0) {
                        zeroedData = false;
                        break;
                    }
                }
                if (zeroedData) {
                    buf.skipBytes(Math.min(buf.readableBytes(), 45));
                }
                return false;
            }
        }

        int mcc = buf.readUnsignedShort();
        int mnc;
        if (BitUtil.check(mcc, 15)) {
            mnc = buf.readUnsignedShort();
        } else {
            mnc = buf.readUnsignedByte();
        }
        int lac;
        lac = buf.readUnsignedShort();

        long cid;
        cid = buf.readUnsignedMedium();

        position.setNetwork(new Network(CellTower.from(BitUtil.to(mcc, 15), mnc, lac, cid)));

        if (length > 9) {
            buf.skipBytes(length - 9);
        }

        return true;
    }

    private void decodeStatus(Position position, ByteBuf buf) {

        int status = buf.readUnsignedByte();
        position.set(Position.KEY_STATUS, status);
        // Bit6: Gps tracking on/off
        position.set(Position.KEY_GPS, BitUtil.check(status, 6));
        position.set(Position.KEY_IGNITION, BitUtil.check(status, 1));
        // LOGGER.info("Kontak Durumu HBT Paketinden alındı");
        position.set(Position.KEY_CHARGE, BitUtil.check(status, 2));
        position.set(Position.KEY_BLOCKED, BitUtil.check(status, 7));
        // Bit0: Activated/Deactivated
        position.set(Position.KEY_ARMED, BitUtil.check(status, 0));
        // SEEWORLD R11 HBT TERMİNAL İNFORMATİON

        switch (BitUtil.between(status, 3, 6)) {
            case 1:
                position.set(Position.KEY_ALARM, Position.ALARM_VIBRATION);
                break;
            case 2:
                position.set(Position.KEY_ALARM, Position.ALARM_POWER_CUT);
                break;
            case 3:
                position.set(Position.KEY_ALARM, Position.ALARM_LOW_BATTERY);
                break;
            case 4:
                position.set(Position.KEY_ALARM, Position.ALARM_SOS);
                break;
            default:
                break;
        }

    }

    private String decodeAlarm(short value) {
        switch (value) {
            case 0x01:
                return Position.ALARM_SOS;
            case 0x02:
                return Position.ALARM_POWER_CUT;
            case 0x03:
            case 0x09:
                return Position.ALARM_VIBRATION;
            case 0x04:
                return Position.ALARM_GEOFENCE_ENTER;
            case 0x05:
                return Position.ALARM_GEOFENCE_EXIT;
            case 0x06:
                return Position.ALARM_OVERSPEED;
            case 0x0E:
            case 0x0F:
            case 0x19:
                return Position.ALARM_LOW_BATTERY;
            case 0x11:
                return Position.ALARM_POWER_OFF;
            case 0x0C:
                return Position.KEY_POWER;
            case 0x13:
            case 0x25:
                return Position.ALARM_TAMPERING;
            case 0x14:
                return Position.ALARM_DOOR;
            case 0x18:
                return Position.ALARM_REMOVING;
            case 0x23:
                return Position.ALARM_FALL_DOWN;
            case 0x26:
                return Position.ALARM_ACCELERATION;
            case 0x27:
                return Position.ALARM_BRAKING;
            case 0x2A:
            case 0x28:
                return Position.ALARM_CORNERING;
            case 0x29:
                return Position.ALARM_ACCIDENT;
            default:
                return null;
        }
    }

    private Object decodeBasic(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        int length = buf.readUnsignedByte();
        int dataLength = length - 5;
        int type = buf.readUnsignedByte();
        int type2 = MSG_COMMAND_0;

        Position position = new Position(getProtocolName());
        DeviceSession deviceSession = null;
        if (type != MSG_LOGIN) {
            deviceSession = getDeviceSession(channel, remoteAddress);
            if (deviceSession == null) {
                return null;
            }
            position.setDeviceId(deviceSession.getDeviceId());
            if (!deviceSession.contains(DeviceSession.KEY_TIMEZONE)) {
                deviceSession.set(DeviceSession.KEY_TIMEZONE, getTimeZone(deviceSession.getDeviceId()));
            }
        }

        if (type == MSG_LOGIN) {

            String imei = ByteBufUtil.hexDump(buf.readSlice(8)).substring(1);
            buf.readUnsignedShort(); // type

            deviceSession = getDeviceSession(channel, remoteAddress, imei);
            if (deviceSession != null && !deviceSession.contains(DeviceSession.KEY_TIMEZONE)) {
                deviceSession.set(DeviceSession.KEY_TIMEZONE, getTimeZone(deviceSession.getDeviceId()));
                // Login sonrası konum filtreleme için işaretler
                deviceSession.setSkipFirstLocation(true);
                deviceSession.setLoginMessageReceived(true);
            }

            if (dataLength > 10) {
                int extensionBits = buf.readUnsignedShort();
                int hours = (extensionBits >> 4) / 100;
                int minutes = (extensionBits >> 4) % 100;
                int offset = (hours * 60 + minutes) * 60;
                if ((extensionBits & 0x8) != 0) {
                    offset = -offset;
                }
                if (deviceSession != null) {
                    TimeZone timeZone = deviceSession.get(DeviceSession.KEY_TIMEZONE);
                    if (timeZone.getRawOffset() == 0) {
                        timeZone.setRawOffset(offset * 1000);
                        deviceSession.set(DeviceSession.KEY_TIMEZONE, timeZone);
                    }
                }
                deviceSession.setSkipFirstLocation(true);
            }

            if (deviceSession != null) {
                sendResponse(channel, false, type, buf.getShort(buf.writerIndex() - 6), null);
                deviceSession.setSkipFirstLocation(true);
            }

            return null;

        } else if (type == MSG_INFO) {

            getLastLocation(position, null);

            position.set(Position.KEY_POWER, buf.readShort() * 0.01);// kontrol edilecek

            return position;

        } else if (type == MSG_LBS_EXTEND) {

            DateBuilder dateBuilder = new DateBuilder((TimeZone) deviceSession.get(DeviceSession.KEY_TIMEZONE))
                    .setDate(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte())
                    .setTime(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte());

            getLastLocation(position, dateBuilder.getDate());

            // MCC (2 byte) okuyun
            int mcc = buf.readUnsignedShort();
            // MNC (1 byte) okuyun
            int mnc = buf.readUnsignedByte();

            // Network nesnesi oluşturun
            Network network = new Network();

            // Hücre bilgilerini okuyun
            int lac = buf.readUnsignedShort();
            int cid = buf.readUnsignedShort();
            network.addCellTower(CellTower.from(mcc, mnc, lac, cid));

            // Network nesnesini konuma ayarlayın
            position.setNetwork(network);
        } else if (type == MSG_STRING) {

            getLastLocation(position, null);

            int commandLength = buf.readUnsignedByte();

            if (commandLength > 0) {
                buf.readUnsignedInt(); // server flag (reserved)
                String data = buf.readSlice(commandLength - 4).toString(StandardCharsets.US_ASCII);
                if (data.startsWith("<ICCID:")) {
                    position.set(Position.KEY_ICCID, data.substring(7, 27));
                } else if (data.startsWith("GPSON_OK!")) {
                    // GPSON_OK! ifadesi için POSITION.KEY_ARCHIVE olarak kaydet
                    position.set(Position.KEY_ARCHIVE, data);
                } else {
                    position.set(Position.KEY_RESULT, data);
                }
            }

        } else if (isSupported(type)) {

            if (hasGps(type)) {
                decodeGps(position, buf, false, deviceSession.get(DeviceSession.KEY_TIMEZONE));// uyarıkalacak
            } else if (type == MSG_ALARM_STATUS) {

                buf.skipBytes(18); // Alarm paketinde 18 byte atla
                getLastLocation(position, null);

            } else {
                getLastLocation(position, null);
            }

            if (hasLbs(type) && buf.readableBytes() > 6) {
                boolean hasLength = hasStatus(type)
                        && (type != MSG_ALARM_STATUS || variant != Variant.VXT01);
                decodeLbs(position, buf, type, hasLength);
            }

            if (hasStatus(type)) {
                decodeStatus(position, buf);

                position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte() * 100 / 6);
                position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                if (type == MSG_ALARM_STATUS) {// ALARM PAKETİ İSE ALARM DEĞERİ DÖNDÜR
                    position.set(Position.KEY_ALARM, decodeAlarm(buf.readUnsignedByte()));
                } else {// HBT PAKETİYSE VOLTAJ DEĞERİ DÖNDÜR
                    int hexValue = buf.readUnsignedByte(); // Okunan hexadecimal değeri alın Takiponr12
                    double decimalValue = hexValue; // Hexadecimal değeri decimal'e çevirin takipon
                    position.set(Position.KEY_POWER, decimalValue);
                }
                position.set("workingmode", buf.readUnsignedByte());

                // key_ignition durumunu kontrol et ve geçişi tespit et
                boolean currentIgnition = position.getBoolean(Position.KEY_IGNITION); // currentIgnition'ı position'dan
                                                                                      // alıyoruz
                // boolean previousIgnition = deviceSession.getPreviousIgnition();

                /*
                 * // key_ignition durumu açıktan kapalıya geçişi kontrol et
                 * if (previousIgnition && !currentIgnition) {
                 * // Gönderilecek mesajı oluştur
                 * String message = "gpson,on#";
                 * ByteBuf messageBuf = Unpooled.buffer();
                 * // Sabit parametreler (0x0D000000)
                 * messageBuf.writeByte(0x0D);
                 * messageBuf.writeInt(0x00000000);
                 * // Mesaj verisini ekle
                 * messageBuf.writeBytes(message.getBytes(StandardCharsets.US_ASCII));
                 * sendResponse(channel, false, type2, buf.getShort(buf.writerIndex() - 6),
                 * messageBuf);
                 * LOGGER.debug("Kontak kapatıldıktan sonra konum güncelleme komutu gönderildi"
                 * );
                 * }
                 */
                // Güncellenmiş currentIgnition durumunu sakla
                deviceSession.setPreviousIgnition(currentIgnition);
            }

            if (type == MSG_GPS_LBS_1) {
                // Login sonrası ilk konumsa cihaza işlenmiş gibi cevap ver ve sunucuda filtrele
                if (deviceSession.shouldSkipFirstLocation()) {
                    // *** EKLENEN KISIM: Kontak açıksa filtreyi bypass et ***
                    boolean ignitionOn = position.getBoolean(Position.KEY_IGNITION);
                    if (ignitionOn) {
                        deviceSession.setSkipFirstLocation(false);
                        deviceSession.setLoginMessageReceived(false);
                        deviceSession.resetLocationCount();
                        LOGGER.info("Kontak açık, login filtresi kaldırıldı");
                        // filtreyi atla, pozisyonu işlemeye devam et
                    } else {
                        sendResponse(channel, false, type, buf.getShort(buf.writerIndex() - 6), null);
                        LOGGER.info("Giriş sonrası ilk konumlar filtrelendi");
                        // Konum sayacını arttır
                        if (deviceSession.isLoginMessageReceived()) {
                            deviceSession.incrementLocationCount();
                            // Örnek: 3 konumdan sonra login filtresini kapat
                            if (deviceSession.getLocationCount() >= 2) {
                                deviceSession.setLoginMessageReceived(false);
                                deviceSession.setSkipFirstLocation(false);
                                deviceSession.resetLocationCount();
                                LOGGER.info("Login sonrası konum filtresi kaldırıldı");
                            }
                        }
                        return null; // Bu konumu işlemeden atla
                    }
                    if (variant == Variant.GT06E_CARD) {
                        position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());
                        String data = buf.readCharSequence(buf.readUnsignedByte(), StandardCharsets.US_ASCII)
                                .toString();
                        buf.readUnsignedByte(); // alarms
                        buf.readUnsignedByte(); // swiped
                        position.set(Position.KEY_CARD, data.trim());
                    } else if (variant == Variant.BENWAY) {
                        int mask = buf.readUnsignedShort();
                        position.set(Position.KEY_IGNITION, BitUtil.check(mask, 8 + 7));
                        // LOGGER.info("Kontak Durumu 3 nodan alındı - hiç kullanılmıyor");

                        position.set(Position.PREFIX_IN + 2, BitUtil.check(mask, 8 + 6));
                        if (BitUtil.check(mask, 8 + 4)) {
                            int value = BitUtil.to(mask, 8 + 1);
                            if (BitUtil.check(mask, 8 + 1)) {
                                value = -value;
                            }
                            position.set(Position.PREFIX_TEMP + 1, value);
                        } else {
                            int value = BitUtil.to(mask, 8 + 2);
                            if (BitUtil.check(mask, 8 + 5)) {
                                position.set(Position.PREFIX_ADC + 1, value);
                            } else {
                                position.set(Position.PREFIX_ADC + 1, value * 0.1);
                            }
                        }
                    } else if (variant == Variant.VXT01) {
                        decodeStatus(position, buf);
                        position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.01);
                        position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                        buf.readUnsignedByte(); // alarm extension
                    } else if (variant == Variant.S5) {
                        decodeStatus(position, buf);
                        position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.01);
                        position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                        position.set(Position.KEY_ALARM, decodeAlarm(buf.readUnsignedByte()));
                        position.set("oil", buf.readUnsignedShort());
                        int temperature = buf.readUnsignedByte();
                        if (BitUtil.check(temperature, 7)) {
                            temperature = -BitUtil.to(temperature, 7);
                        }
                        position.set(Position.PREFIX_TEMP + 1, temperature);
                        position.set(Position.KEY_ODOMETER, buf.readUnsignedInt() * 10);
                    } else if (variant == Variant.WETRUST) {
                        position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());
                        position.set(Position.KEY_CARD, buf.readCharSequence(
                                buf.readUnsignedByte(), StandardCharsets.US_ASCII).toString());
                        position.set(Position.KEY_ALARM, buf.readUnsignedByte() > 0 ? Position.ALARM_GENERAL : null);
                        position.set("cardStatus", buf.readUnsignedByte());
                        position.set(Position.KEY_DRIVING_TIME, buf.readUnsignedShort());
                    }
                }
            }

            if (buf.readableBytes() == 4 + 6) {
                position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());
            }

        } else {

            if (dataLength > 0) {
                buf.skipBytes(dataLength);
            }
            if (type != MSG_COMMAND_0) {
                sendResponse(channel, false, type, buf.getShort(buf.writerIndex() - 6), null);
            }
            return null;

        }

        sendResponse(channel, false, type, buf.getShort(buf.writerIndex() - 6), null);

        return position;
    }

    private Object decodeExtended(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress);
        if (deviceSession == null) {
            return null;
        }

        if (!deviceSession.contains(DeviceSession.KEY_TIMEZONE)) {
            deviceSession.set(DeviceSession.KEY_TIMEZONE, getTimeZone(deviceSession.getDeviceId()));
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        buf.readUnsignedShort(); // length
        int type = buf.readUnsignedByte();

        if (type == MSG_INFO) {

            int subType = buf.readUnsignedByte();

            getLastLocation(position, null);

            if (subType == 0x00) {

                position.set(Position.PREFIX_ADC + 1, buf.readUnsignedShort() * 0.01);
                return position;

            } else if (subType == 0x04) {

                CharSequence content = buf.readCharSequence(buf.readableBytes() - 4 - 2, StandardCharsets.US_ASCII);
                String[] values = content.toString().split(";");
                for (String value : values) {
                    String[] pair = value.split("=");
                    switch (pair[0]) {
                        case "ALM1":
                        case "ALM2":
                        case "ALM3":
                            position.set("alarm" + pair[0].charAt(3) + "Status", Integer.parseInt(pair[1], 16));
                        case "STA1":
                            position.set("otherStatus", Integer.parseInt(pair[1], 16));
                            break;
                        case "DYD":
                            position.set("engineStatus", Integer.parseInt(pair[1], 16));
                            break;
                        default:
                            break;
                    }
                }
                return position;

            } else if (subType == 0x05) {

                if (buf.readableBytes() >= 6 + 1 + 6) {
                    DateBuilder dateBuilder = new DateBuilder((TimeZone) deviceSession.get(DeviceSession.KEY_TIMEZONE))
                            .setDate(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte())
                            .setTime(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte());
                    position.setDeviceTime(dateBuilder.getDate());
                }

                int flags = buf.readUnsignedByte();
                position.set(Position.KEY_DOOR, BitUtil.check(flags, 0));
                position.set(Position.PREFIX_IO + 1, BitUtil.check(flags, 2));
                return position;

            } else if (subType == 0x0a) {

                buf.skipBytes(8); // imei
                buf.skipBytes(8); // imsi
                position.set(Position.KEY_ICCID, ByteBufUtil.hexDump(buf.readSlice(10)).replaceAll("f", ""));
                return position;

            } else if (subType == 0x0d) {

                if (buf.getByte(buf.readerIndex()) != '!') {
                    buf.skipBytes(6);
                }

                return position;

            } else if (subType == 0x1b) {

                if (Character.isLetter(buf.getUnsignedByte(buf.readerIndex()))) {
                    String data = buf.readCharSequence(buf.readableBytes() - 6, StandardCharsets.US_ASCII).toString();
                    position.set("serial", data.trim());
                } else {
                    buf.readUnsignedByte(); // header
                    buf.readUnsignedByte(); // type
                    position.set(Position.KEY_DRIVER_UNIQUE_ID, ByteBufUtil.hexDump(buf.readSlice(4)));
                    buf.readUnsignedByte(); // checksum
                    buf.readUnsignedByte(); // footer
                }
                return position;

            }

        }

        return null;
    }

    private void decodeVariant(ByteBuf buf) {
        int header = buf.getUnsignedShort(buf.readerIndex());
        int length;
        int type;
        if (header == 0x7878) {
            length = buf.getUnsignedByte(buf.readerIndex() + 2);
            type = buf.getUnsignedByte(buf.readerIndex() + 2 + 1);
        } else {
            length = buf.getUnsignedShort(buf.readerIndex() + 2);
            type = buf.getUnsignedByte(buf.readerIndex() + 2 + 2);
        }

        if (header == 0x7878 && type == MSG_GPS_LBS_1 && length == 0x24) {
            variant = Variant.VXT01;
        } else if (header == 0x7878 && type == MSG_ALARM_STATUS && length == 0x24) {
            variant = Variant.VXT01;
        } else if (header == 0x7878 && type == MSG_GPS_LBS_1 && length >= 0x71) {
            variant = Variant.GT06E_CARD;
        } else if (header == 0x7878 && type == MSG_GPS_LBS_1 && length == 0x21) {
            variant = Variant.BENWAY;
        } else if (header == 0x7878 && type == MSG_GPS_LBS_1 && length == 0x2b) {
            variant = Variant.S5;
        } else if (header == 0x7878 && type == MSG_STATUS && length == 0x13) {
            variant = Variant.OBD6;
        } else if (header == 0x7878 && type == MSG_GPS_LBS_1 && length == 0x29) {
            variant = Variant.WETRUST;
        } else {
            variant = Variant.STANDARD;
        }
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        decodeVariant(buf);

        int header = buf.readShort();

        if (header == 0x7878) {
            return decodeBasic(channel, remoteAddress, buf);
        } else {
            return decodeExtended(channel, remoteAddress, buf);
        }
    }

}
