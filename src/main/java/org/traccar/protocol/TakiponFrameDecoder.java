
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.traccar.BaseFrameDecoder;

public class TakiponFrameDecoder extends BaseFrameDecoder {

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        if (buf.readableBytes() < 5) {
            return null;
        }

        int length = 2 + 2; // head and tail

        if (buf.getByte(buf.readerIndex()) == 0x78) {
            length += 1 + buf.getUnsignedByte(buf.readerIndex() + 2);
        } else {
            length += 2 + buf.getUnsignedShort(buf.readerIndex() + 2);
        }

        if (buf.readableBytes() >= length && buf.getUnsignedShort(buf.readerIndex() + length - 2) == 0x0d0a) {
            return buf.readRetainedSlice(length);
        }

        int endIndex = buf.readerIndex() - 1;
        do {
            endIndex = buf.indexOf(endIndex + 1, buf.writerIndex(), (byte) 0x0d);
            if (endIndex > 0 && buf.writerIndex() > endIndex + 1 && buf.getByte(endIndex + 1) == 0x0a) {
                return buf.readRetainedSlice(endIndex + 2 - buf.readerIndex());
            }
        } while (endIndex > 0);

        return null;
    }

}
