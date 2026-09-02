package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterFactory;

@Activate(order = -10_000)
public final class LaneExactInstanceRouterFactory implements RouterFactory {

    @Override
    public Router getRouter(URL url) {
        return new LaneExactInstanceRouter(url);
    }
}
