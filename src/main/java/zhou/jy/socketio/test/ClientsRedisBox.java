package zhou.jy.socketio.test;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.DisconnectableHub;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.Transport;
import com.corundumstudio.socketio.ack.AckManager;
import com.corundumstudio.socketio.handler.ClientHead;
import com.corundumstudio.socketio.handler.ClientsBox;
import com.corundumstudio.socketio.scheduler.CancelableScheduler;
import com.corundumstudio.socketio.store.StoreFactory;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import redis.clients.jedis.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.*;

/**
 * @author zhoujy
 * @date 2019/04/10
 */
public class ClientsRedisBox extends ClientsBox {

    private final static String CLIENT_HEAD = "clientheads";

    private AckManager ackManager;

    private DisconnectableHub disconnectable;

    private StoreFactory storeFactory;

    private CancelableScheduler disconnectScheduler;

    private Configuration configuration;

    private  ShardedJedisPool pool;

    public ClientsRedisBox() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(100);
        config.setMaxIdle(50);
        config.setMaxWaitMillis(3000);
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);
        // 集群
        JedisShardInfo jedisShardInfo1 = new JedisShardInfo("32d958e9f0504220186.redis.rds.aliyuncs.com", 6379);
        jedisShardInfo1.setPassword("xavYovNLzoxGy3zC");
        List<JedisShardInfo> list = new LinkedList<JedisShardInfo>();
        list.add(jedisShardInfo1);
        pool = new ShardedJedisPool(config, list);
    }

    public ClientsRedisBox(AckManager ackManager, DisconnectableHub disconnectable, StoreFactory storeFactory, CancelableScheduler disconnectScheduler, Configuration configuration) {
        this.ackManager = ackManager;
        this.disconnectable = disconnectable;
        this.storeFactory = storeFactory;
        this.disconnectScheduler = disconnectScheduler;
        this.configuration = configuration;

        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(100);
        config.setMaxIdle(50);
        config.setMaxWaitMillis(3000);
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);
        // 集群
        JedisShardInfo jedisShardInfo1 = new JedisShardInfo("32d958e9f0504220186.redis.rds.aliyuncs.com", 6379);
        jedisShardInfo1.setPassword("xavYovNLzoxGy3zC");
        List<JedisShardInfo> list = new LinkedList<JedisShardInfo>();
        list.add(jedisShardInfo1);
        pool = new ShardedJedisPool(config, list);
    }

    public AckManager getAckManager() {
        return ackManager;
    }

    public void setAckManager(AckManager ackManager) {
        this.ackManager = ackManager;
    }

    public DisconnectableHub getDisconnectable() {
        return disconnectable;
    }

    public void setDisconnectable(DisconnectableHub disconnectable) {
        this.disconnectable = disconnectable;
    }

    public StoreFactory getStoreFactory() {
        return storeFactory;
    }

    public void setStoreFactory(StoreFactory storeFactory) {
        this.storeFactory = storeFactory;
    }

    public CancelableScheduler getDisconnectScheduler() {
        return disconnectScheduler;
    }

    public void setDisconnectScheduler(CancelableScheduler disconnectScheduler) {
        this.disconnectScheduler = disconnectScheduler;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    public HandshakeData getHandshakeData(UUID sessionId) {
        return super.getHandshakeData(sessionId);
    }

    @Override
    public void addClient(ClientHead clientHead) {
        super.addClient(clientHead);
        Map<String, List<String>> headers = new HashMap<>();
        for (String name : clientHead.getHandshakeData().getHttpHeaders().names()) {
            List<String> values = new ArrayList<>(clientHead.getHandshakeData().getHttpHeaders().getAll(name));
            headers.put(name, values);
        }
        ClientHeadData clientHeadData = new ClientHeadData(clientHead.getSessionId(), headers,
                clientHead.getHandshakeData().getUrlParams(),
                clientHead.getHandshakeData().getAddress(),
                clientHead.getHandshakeData().getTime(),
                clientHead.getHandshakeData().getLocal(),
                clientHead.getHandshakeData().getUrl(),
                clientHead.getHandshakeData().isXdomain(),
                clientHead.getCurrentTransport().getValue());
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(clientHeadData);
            ShardedJedis resource = pool.getResource();
            try {
                System.out.println("add session:"+clientHead.getSessionId());
                resource.hset(CLIENT_HEAD.getBytes(), clientHead.getSessionId().toString().getBytes(), byteArrayOutputStream.toByteArray());
            }finally {
                resource.close();
            }
            byteArrayOutputStream.close();
            objectOutputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void removeClient(UUID sessionId) {
        super.removeClient(sessionId);
    }

    @Override
    public ClientHead get(UUID sessionId) {
        System.out.println("get client head:"+sessionId);
        ClientHead clientHead = super.get(sessionId);
        if (clientHead == null) {
            try {
                ShardedJedis resource = pool.getResource();
                try {
                    if (!resource.hexists(CLIENT_HEAD.getBytes(), sessionId.toString().getBytes())) {
                        return null;
                    }
                    byte[] bytes = resource.hget(CLIENT_HEAD.getBytes(), sessionId.toString().getBytes());
                    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
                    ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
                    ClientHeadData clientHeadData = (ClientHeadData) objectInputStream.readObject();
                    HttpHeaders httpHeaders = new DefaultHttpHeaders();
                    clientHeadData.getHeaders().forEach((name, values) -> {
                        httpHeaders.add(name, values);
                    });
                    HandshakeData handshakeData = new HandshakeData(httpHeaders, clientHeadData.getUrlParams(), clientHeadData.getAddress(),
                            clientHeadData.getLocal(), clientHeadData.getUrl(), clientHeadData.isXdomain());
                    clientHead = new ClientHead(sessionId, ackManager, disconnectable, storeFactory, handshakeData,
                            this, Transport.byName(clientHeadData.getTransport()), disconnectScheduler, configuration);
                    System.out.println("从redis获取session："+sessionId);
                    super.addClient(clientHead);
                }finally {
                    resource.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return clientHead;
    }

    @Override
    public void add(Channel channel, ClientHead clientHead) {
        super.add(channel, clientHead);
    }

    @Override
    public void remove(Channel channel) {
        super.remove(channel);
    }

    @Override
    public ClientHead get(Channel channel) {
        return super.get(channel);
    }
}
