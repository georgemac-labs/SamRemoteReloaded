package mkg20001.net.samremotecommon;

public final class TvDevice {
    public enum Protocol { LEGACY, TIZEN }
    public final String host, id, name, token;
    public final Protocol protocol;
    public final int port;

    public TvDevice(String host, String id, String name, Protocol protocol, int port, String token) {
        this.host = host;
        this.id = id == null ? host : id;
        this.name = name == null || name.isEmpty() ? host : name;
        this.protocol = protocol;
        this.port = port;
        this.token = token == null ? "" : token;
    }

    public TvDevice withToken(String value) {
        return new TvDevice(host, id, name, protocol, port, value);
    }
}
