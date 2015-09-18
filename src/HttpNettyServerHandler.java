import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpHeaders.Values;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
/**
 * Created by Dmitry on 15.09.2015.
 */
public class HttpNettyServerHandler extends ChannelDuplexHandler {
    static final AtomicLong TOTAL_REQUESTS = new AtomicLong();
    static final ConcurrentHashMap<String, AtomicLong> REQUESTS_PER_IP = new ConcurrentHashMap<String, AtomicLong>();
    static final ConcurrentHashMap<String, Long> LAST_REQUEST_TIME_PER_IP = new ConcurrentHashMap<String, Long>();
    static final ConcurrentHashMap<String, AtomicLong> REDIRECTS_PER_URL = new ConcurrentHashMap<String, AtomicLong>();
    static final AtomicLong OPENED_CONNECTIONS = new AtomicLong();
    static final ConcurrentLinkedQueue<StatisticsOfConnections> STATISTICS_OF_CONNECTIONS = new ConcurrentLinkedQueue<StatisticsOfConnections>();
    static final AtomicInteger SIZE_OF_STATISTICS = new AtomicInteger(0);

    private String remoteHostName;
    private String uri;

    private final int timeout = 10000;

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            TOTAL_REQUESTS.incrementAndGet();
            remoteHostName = ((NioSocketChannel) ctx.channel()).remoteAddress().getHostName();

            AtomicLong requestsCounter = REQUESTS_PER_IP.putIfAbsent(remoteHostName, new AtomicLong(1L));
            if (requestsCounter != null) requestsCounter.incrementAndGet();

            LAST_REQUEST_TIME_PER_IP.put(remoteHostName, System.currentTimeMillis());

            HttpRequest req = (HttpRequest) msg;
            uri = req.getUri();
            QueryStringDecoder decoder = new QueryStringDecoder(req.getUri());
            HttpMethod method = req.getMethod();

            if (method.equals(HttpMethod.GET) && decoder.path().equals("/hello")) {
                Thread.sleep(timeout);
                System.out.println("Hello");

                if (HttpHeaders.is100ContinueExpected(req)) {
                    ctx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
                }
                boolean keepAlive = HttpHeaders.isKeepAlive(req);
                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer("Hello World".getBytes()));
                response.headers().set(CONTENT_TYPE, "text/plain");
                response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                System.out.println("keepAlive: " + keepAlive);
                if (!keepAlive) {
                    ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                } else {
                    response.headers().set(CONNECTION, Values.KEEP_ALIVE);
                    ctx.write(response);
                }
            } else if (method.equals(HttpMethod.GET) && decoder.path().equals("/redirect") && decoder.parameters().containsKey("url")) {
                String redirectTo = decoder.parameters().get("url").get(0);

                AtomicLong redirectCounter = REDIRECTS_PER_URL.putIfAbsent(redirectTo, new AtomicLong(1L));
                if (redirectCounter != null)
                    redirectCounter.incrementAndGet();

                System.out.println("redirect to " + redirectTo);

                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FOUND);
                response.headers().set(HttpHeaders.Names.LOCATION, redirectTo);

                // Close the connection as soon as the error message is sent.
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } else if (method.equals(HttpMethod.GET) && decoder.path().equals("/status")) {
                sendStatistics(ctx);
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        OPENED_CONNECTIONS.incrementAndGet();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        OPENED_CONNECTIONS.decrementAndGet();
        super.channelInactive(ctx);
    }

    private void addStatistics(StatisticsOfConnections statisticsOfConnections) {
        statisticsOfConnections.setIp(remoteHostName);
        statisticsOfConnections.setUri(uri);
        STATISTICS_OF_CONNECTIONS.add(statisticsOfConnections);
        if (SIZE_OF_STATISTICS.incrementAndGet() > 16) {
            STATISTICS_OF_CONNECTIONS.remove();
            SIZE_OF_STATISTICS.decrementAndGet();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof StatisticsOfConnections) {
            addStatistics((StatisticsOfConnections) evt);
        }

        super.userEventTriggered(ctx, evt);
    }

    private static void sendStatistics(ChannelHandlerContext ctx) {
        Long totalRequestsCount = TOTAL_REQUESTS.get();
        Integer uniqueRequestsCount = REQUESTS_PER_IP.size();

        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");

        StringBuilder buf = new StringBuilder();
        String eol = "\r\n";

        buf.append("<!DOCTYPE html>" + eol);
        buf.append("<html><head><title>");
        buf.append("Statistics");
        buf.append("</title></head><body><TR align=\"center\">" + eol);
        buf.append("<h3>Total requests =" + totalRequestsCount + " <br>" + eol);
        buf.append("Unique requests= " + uniqueRequestsCount + "<br>" + eol);
        buf.append("Opened connections = " + OPENED_CONNECTIONS.get() + eol);
        countersOfIP(buf);
        redirectsCounter(buf);
        logLast16Connections(buf);
        buf.append("</h3></TR></body></html>");
        ByteBuf buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8);
        response.content().writeBytes(buffer);
        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
        buffer.release();

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void logLast16Connections(final StringBuilder buf) {
        buf.append("<TABLE border=\"1\">");
        buf.append("<caption>Last Connections log</caption><br>");
        buf.append("<TR align=\"center\"><td>IP</td> <td>URL</td><td>Timestamp</td><td>Sent bytes</td><td>Received bytes</td><td>Speed (bytes/sec)</td></TR><br>");

        STATISTICS_OF_CONNECTIONS.iterator().forEachRemaining(new Consumer<StatisticsOfConnections>() {
            @Override
            public void accept(StatisticsOfConnections statisticsOfConnections) {
                buf.append(statisticsOfConnections.getTable());
            }
        });
    }

    //- счетчик запросов на каждый IP в виде таблицы с колонкам и IP,
    //    кол-во запросов, время последнего запроса
    static void countersOfIP(StringBuilder buf) {
//        buf.append("Requests per IP:<br>");
        buf.append("<TABLE border=\"1\">");
        buf.append("<caption>Requests per IP info table</caption><br>");
        buf.append("<TR align=\"center\"><td align=\"center\">IP</td> <td align=\"center\">Count </td><td>Time</td></TR><br>");
        Enumeration<String> enumeration = REQUESTS_PER_IP.keys();

        while (enumeration.hasMoreElements()) {
            String key = enumeration.nextElement();
            Long requests = REQUESTS_PER_IP.get(key).get();
            String lastRequestTime = new Date(LAST_REQUEST_TIME_PER_IP.get(key)).toString();
            buf.append(String.format("<tr><td>%s</td> <td align=\"center\">%d </td> <td>%s</td></tr> <br>", key, requests, lastRequestTime));
        }
    }

    static void redirectsCounter(StringBuilder buf) {
        if (REDIRECTS_PER_URL.size() == 0)
            return;

        buf.append("<TABLE border=\"1\">");
        buf.append("<caption>Redirects info table</caption><br>");
        buf.append("<TR><td align=\"center\">URL</td> <td align=\"center\">Count </td></TR><br>");

        for (Enumeration<String> e = REDIRECTS_PER_URL.keys(); e.hasMoreElements(); ) {
            String url = e.nextElement();
            buf.append(String.format("<tr><td>%s</td> <td align=\"center\">%d </td></tr><br>", url, REDIRECTS_PER_URL.get(url).get()));
        }
        buf.append("</TABLE>");
    }
}
