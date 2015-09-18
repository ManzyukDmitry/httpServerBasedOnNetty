import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
/**
 * Created by Dmitry on 15.09.2015.
 */
public class StatisticsHandler extends ChannelDuplexHandler {
    private Long serverStartTime = 0L;
    private Integer receivedBytes = 0;
    private Integer sentBytes = 0;
    private Long serverEndTime = 0L;
    private StatisticsOfConnections statisticsOfConnections = new StatisticsOfConnections();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        statisticsOfConnections.setCurrentTime(System.currentTimeMillis());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        statisticsOfConnections.setValues(receivedBytes.longValue(), sentBytes.longValue(), serverEndTime - serverStartTime);
        ctx.fireUserEventTriggered(statisticsOfConnections);
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;

            if (serverStartTime == 0)
                serverStartTime = System.currentTimeMillis();

            receivedBytes += buf.readableBytes();
        }

        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf)msg;
            sentBytes += buf.readableBytes();
            serverEndTime = System.currentTimeMillis();
        }

        super.write(ctx, msg, promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise future) throws Exception {
        super.close(ctx, future);
    }
}
