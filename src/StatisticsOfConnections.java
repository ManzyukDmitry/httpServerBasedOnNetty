import java.util.Date;
/**
 * Created by Dmitry on 15.09.2015.
 */
public class StatisticsOfConnections {
    private String ip;
    private String uri;
    private long currentTime;
    private long duration;
    private long sentBytes;
    private long receivedBytes;
    

    public StatisticsOfConnections() {
        this.currentTime = System.currentTimeMillis();
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public void setCurrentTime(Long currentTime) {
        this.currentTime = currentTime;
    }

    public void setValues(Long receivedBytes, Long sentBytes, Long duration) {
        this.receivedBytes += receivedBytes;
        this.sentBytes += sentBytes;
        this.duration += duration;
    }

    public Double getSpeed() {
        return (sentBytes + receivedBytes) / (new Long(duration).doubleValue() / 1000);
    }

    public String getTable() {
        return String.format("<tr><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%d</td><td>%.2f</td></tr>", ip, uri, new Date(currentTime).toString(), sentBytes, receivedBytes, getSpeed());
    }
}
