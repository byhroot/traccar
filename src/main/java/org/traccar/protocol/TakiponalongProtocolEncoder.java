
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.config.Keys;
import org.traccar.helper.Checksum;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Command;
import org.traccar.model.Device;

import java.nio.charset.StandardCharsets;

public class TakiponalongProtocolEncoder extends BaseProtocolEncoder {

    public TakiponalongProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    private ByteBuf encodeContent(long deviceId, String content) {

        boolean language = AttributeUtil.lookup(
                getCacheManager(), Keys.PROTOCOL_LANGUAGE.withPrefix(getProtocolName()), deviceId);

        ByteBuf buf = Unpooled.buffer();

        buf.writeByte(0x78);
        buf.writeByte(0x78);

        buf.writeByte(1 + 1 + 4 + content.length() + 2 + 2 + (language ? 2 : 0)); // message length

        buf.writeByte(TakiponalongProtocolDecoder.MSG_COMMAND_0);

        buf.writeByte(4 + content.length()); // command length
        buf.writeInt(0);
        buf.writeBytes(content.getBytes(StandardCharsets.US_ASCII)); // command

        if (language) {
            buf.writeShort(2); // english language
        }

        buf.writeShort(0); // message index

        buf.writeShort(Checksum.crc16(Checksum.CRC16_X25, buf.nioBuffer(2, buf.writerIndex() - 2)));

        buf.writeByte('\r');
        buf.writeByte('\n');

        return buf;
    }

    @Override
    protected Object encodeCommand(Command command) {

        boolean alternative = AttributeUtil.lookup(
                getCacheManager(), Keys.PROTOCOL_ALTERNATIVE.withPrefix(getProtocolName()), command.getDeviceId());

        String password = AttributeUtil.getDevicePassword(
                getCacheManager(), command.getDeviceId(), getProtocolName(), "123456");

        Device device = getCacheManager().getObject(Device.class, command.getDeviceId());

        switch (command.getType()) {
            case Command.TYPE_ENGINE_STOP:
                if ("G109".equals(device.getModel())) {
                    return encodeContent(command.getDeviceId(), "DYD#");
                } else if (alternative) {
                    return encodeContent(command.getDeviceId(), "DYD," + password + "#");
                } else {
                    return encodeContent(command.getDeviceId(), "Relay,1#");
                }
            case Command.TYPE_ENGINE_RESUME:
                if ("G109".equals(device.getModel())) {
                    return encodeContent(command.getDeviceId(), "HFYD#");
                } else if (alternative) {
                    return encodeContent(command.getDeviceId(), "HFYD," + password + "#");
                } else {
                    return encodeContent(command.getDeviceId(), "Relay,0#");
                }
            case Command.TYPE_ALARM_VIBRATIONON:
                return encodeContent(command.getDeviceId(), "SENALM,ON,0#");
            case Command.TYPE_ALARM_VIBRATIONOFF:
                return encodeContent(command.getDeviceId(), "SENALM,OFF#");
            case Command.TYPE_ALARM_VIBRATIONLEVEL:
                String content = "SENLEVEL," + command.getString(Command.KEY_SENSORLEVEL) + "#";
                return encodeContent(command.getDeviceId(), content);
            case Command.TYPE_CUSTOM:
                return encodeContent(command.getDeviceId(), command.getString(Command.KEY_DATA));
            default:
                return null;
        }
    }

}
