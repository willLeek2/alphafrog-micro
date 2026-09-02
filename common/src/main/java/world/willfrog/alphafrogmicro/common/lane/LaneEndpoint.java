package world.willfrog.alphafrogmicro.common.lane;

import java.util.Objects;

/** 一次绑定使用的访问地址。 */
public final class LaneEndpoint {

    private final String address;
    private final int port;

    public LaneEndpoint(String address, int port) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("endpoint.address 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("endpoint.port 不在合法范围");
        }
        this.address = address;
        this.port = port;
    }

    public String address() {
        return address;
    }

    public int port() {
        return port;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LaneEndpoint that)) {
            return false;
        }
        return port == that.port && address.equals(that.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, port);
    }

    @Override
    public String toString() {
        return address + ":" + port;
    }
}
