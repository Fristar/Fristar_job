package com.civism.zookeeper;


import com.civism.zookeeper.listener.Listener;
import com.civism.zookeeper.listener.StateListener;
import com.civism.zookeeper.watcher.WatcherProcess;
import com.civism.zookeeper.watcher.ZkWatcher;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


/**
 * @author star
 * @date 2018/8/3 上午11:51
 */
public class ZkClient {

    private static final Logger logger = LoggerFactory.getLogger(ZkClient.class);

    /**
     * zookeeper 服务地址
     */
    private String hosts;
    /**
     * 会话超时时间
     */
    private int sessionTimeout;
    /**
     * 链接超时时间
     */
    private int connTimeout;

    /**
     * 连接同步锁
     */
    private final Semaphore connLock = new Semaphore(0);

    /**
     * 是否链接成功
     */
    private volatile boolean isConnection = false;
    private ZooKeeper zk;
    private ZkWatcher watcher;
    private WatcherProcess process;

    public ZkClient(String hosts) {
        this(hosts, 30000, 3000);
    }

    public ZkClient(String hosts, int sessionTimeout, int connTimeout) {
        this(hosts, 30000, 3000, 1);
    }


    /**
     * @param hosts             zookeeper服务地址
     * @param sessionTimeout    会话超时时间
     * @param connTimeout       连接超时时间
     * @param watcherThreadSize 处理watcher的线程数
     */
    public ZkClient(String hosts, int sessionTimeout, int connTimeout, int watcherThreadSize) {
        this.hosts = hosts;
        this.sessionTimeout = sessionTimeout;
        this.connTimeout = connTimeout;
        watcher = new ZkWatcher(connLock, this);
        this.process = new WatcherProcess(this, watcherThreadSize);
        this.connection();
    }


    /**
     * 获取节点下的数据
     *
     * @param path 节点路径
     * @return
     */
    public byte[] getData(String path) throws ZkClientException {
        return getData(path, false);
    }


    /**
     * 获取节点下的数据
     *
     * @param path    节点路径
     * @param watcher 是否对该节点进行数据变动监听（只能收到一次变动消息）
     * @return
     * @throws ZkClientException
     */
    public byte[] getData(String path, boolean watcher) throws ZkClientException {
        this.checkStatus();
        try {
            return this.zk.getData(path, watcher, null);
        } catch (Exception e) {
            throw new ZkClientException("getData node " + path, e);
        }
    }


    /**
     * 插入数据
     *
     * @param path
     * @param data
     * @throws ZkClientException
     */
    public void setData(String path, byte[] data) throws ZkClientException {
        this.checkStatus();
        try {
            this.zk.setData(path, data, -1);
        } catch (Exception e) {
            throw new ZkClientException("setData node " + path, e);
        }
    }

    /**
     * 获取child节点信息
     *
     * @param path 路径
     * @throws ZkClientException
     */
    protected List<String> getChild(String path) throws ZkClientException {
        return this.getChild(path, false);
    }

    /**
     * 获取child节点信息
     *
     * @param path 路径
     * @throws ZkClientException
     */
    public List<String> getChild(String path, boolean watcher) throws ZkClientException {
        this.checkStatus();
        try {
            return this.zk.getChildren(path, watcher);
        } catch (Exception e) {
            throw new ZkClientException("getChildren node " + path, e);
        }
    }


    /**
     * 创建节点
     * 不支持多层节点创建
     *
     * @param path 节点路径
     * @param data 节点数据
     * @param mode 节点类型 CreateMode.PERSISTENT 永久节点，CreateMode.EPHEMERAL临时节点
     */
    public String create(String path, byte[] data, CreateMode mode) throws ZkClientException {
        this.checkStatus();
        String createNode;
        try {
            createNode = this.zk.create(path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, mode);
        } catch (Exception e) {
            throw new ZkClientException("create node " + path + ", mode=" + mode.name(), e);
        }
        return createNode;
    }


    /**
     * 创建一个临时节点
     * 不支持多层节点创建
     *
     * @param path     节点路径
     * @param data     节点数据
     * @param reCreate true:session超时重连后自动创建该节点,false:创建普通的临时节点
     */
    public void create(String path, byte[] data, boolean reCreate) throws ZkClientException {
        this.create(path, data, CreateMode.EPHEMERAL);
        if (reCreate) {
            this.process.stubborn(path, data);
        }
    }


    /**
     * 创建一个永久节点
     * 不支持多层节点创建
     *
     * @param path 路径
     * @param data 数据
     * @throws ZkClientException
     */
    public void create(String path, byte[] data) throws ZkClientException {
        this.create(path, data, CreateMode.PERSISTENT);
    }


    /**
     * 支持多层节点创建
     * 创建顽固节点
     *
     * @param path
     * @throws ZkClientException
     */
    public String createStubborn(String path) throws ZkClientException {
        if (path != null && !path.trim().equals("")) {
            if (exists(path)) {
                delete(path);
            }
            String[] paths = path.trim().split("/");
            String p = "";
            for (int i = 0; i < paths.length; i++) {
                String s = paths[i];
                if (s != null && !s.equals("")) {
                    p += "/" + s;
                    if (!exists(p)) {
                        if (i < paths.length - 1) {
                            this.create(p, new byte[1], CreateMode.PERSISTENT);
                        } else {
                            this.create(p, new byte[1], true);
                        }
                    }
                }
            }
        }
        return path;
    }

    /**
     * 支持多层节点创建
     *
     * @param path
     * @param mode
     * @throws ZkClientException
     */
    public String create(String path, CreateMode mode) throws ZkClientException {
        if (path != null && !path.trim().equals("")) {
            if (exists(path)) {
                throw new ZkClientException("Node path: " + path + " already exists.");
            }
            String[] paths = path.trim().split("/");
            String p = "";
            for (int i = 0; i < paths.length; i++) {
                String s = paths[i];
                if (s != null && !s.equals("")) {
                    p += "/" + s;
                    if (!exists(p)) {
                        if (i < paths.length - 1) {
                            this.create(p, new byte[1], CreateMode.PERSISTENT);
                        } else {
                            this.create(p, new byte[1], mode);
                        }
                    }
                }
            }
        }
        return path;
    }


    /**
     * 删除节点
     *
     * @param path 路径
     * @throws ZkClientException
     */
    public void delete(String path) throws ZkClientException {
        this.checkStatus();
        try {
            this.zk.delete(path, -1);
        } catch (Exception e) {
            throw new ZkClientException("delete node " + path, e);
        }
    }

    /**
     * 监听节点的数据变化
     *
     * @param listener
     * @param path
     */
    public void listenData(String path, Listener listener) throws ZkClientException {
        this.listen(path, listener, false, false);
    }

    /**
     * 取消对节点的数据变化监听
     *
     * @param path
     * @throws ZkClientException
     */
    public void unlistenData(String path) throws ZkClientException {
        this.unlisten(path, false, false);
    }

    /**
     * 监听节点的子节点变化
     *
     * @param path
     * @param listener
     * @throws ZkClientException
     */
    public void listenChild(String path, Listener listener) throws ZkClientException {
        this.listen(path, listener, true, false);
    }

    /**
     * 取消对节点的子节点变化监听
     *
     * @param path
     * @throws ZkClientException
     */
    public void unlintenChild(String path) throws ZkClientException {
        this.unlisten(path, true, false);
    }

    /**
     * 监听孩子节点数据变化
     *
     * @param path     父节点地址
     * @param listener 监听器
     */
    public void listenChildData(String path, Listener listener) {
        this.listen(path, listener, false, true);
    }


    /**
     * 监听孩子节点数据变化
     *
     * @param path 父节点地址
     */
    public void unlistenChildData(String path) {
        this.unlisten(path, false, true);
    }

    /**
     * 监听zookeeper信息变化
     *
     * @param path      节点地址
     * @param listener  监听器
     * @param child     true为监听子节点变化，false 为监听节点数据变化
     * @param childData true为监听孩子节点数据变化
     * @throws ZkClientException
     */
    private void listen(String path, Listener listener, boolean child, boolean childData) throws ZkClientException {
        this.checkStatus();
        if (!this.exists(path)) {
            throw new NullPointerException("listen path " + path + "  not found.");
        }
        if (this.process != null) {
            this.process.listen(path, listener, child, childData);
        } else {
            logger.warn("not found WatcherProcess instance,Listening can't be triggered.");
        }
    }

    /**
     * 解除节点监听
     *
     * @param path  节点地址
     * @param child true表示监听节点的子节点变化
     * @throws ZkClientException
     */
    private void unlisten(String path, boolean child, boolean childData) throws ZkClientException {
        this.checkStatus();
        if (!this.exists(path)) {
            throw new NullPointerException("listen path " + path + "  not found.");
        }
        if (this.process != null) {
            this.process.unlisten(path, child, childData);
        }
    }


    /**
     * 目前只支持2种zookeeper状态
     * 1. KeeperState.Expired session 超时
     * 2. KeeperState.Disconnected 连接断开时
     *
     * @param state 监听的状态
     */
    public void listenState(Watcher.Event.KeeperState state, StateListener listener) {
        if (state.getIntValue() == Watcher.Event.KeeperState.Expired.getIntValue()) {
            process.listenState(state, listener);
        } else if (state.getIntValue() == Watcher.Event.KeeperState.Disconnected.getIntValue()) {
            process.listenState(state, listener);
        } else {
            throw new ZkClientException("Listener state not is Expired or Disconnected.");
        }
    }

    /**
     * 取消状态监听
     *
     * @param state
     */
    public void unlistenState(Watcher.Event.KeeperState state) {
        process.nulistenState(state);
    }


    /**
     * 同步阻塞连接zookeeper
     *
     * @throws ZkClientException
     */
    private synchronized void connection() throws ZkClientException {
        if (this.checkConnection()) {
            throw new ZkClientException("Has been connected to the server, please do not repeat connection. host:" + hosts);
        }
        try {
            connLock.drainPermits();
            zk = new ZooKeeper(hosts, sessionTimeout, watcher);
        } catch (IOException e) {
            throw new ZkClientException("Connect zookeeper fail, hosts=" + hosts, e);
        }
        try {
            watcher.setWatcherProcess(process);
            boolean isConn = connLock.tryAcquire(connTimeout, TimeUnit.MILLISECONDS);
            if (!isConn) {
                zk.close();
                logger.warn("zookeeper connection timeout. host:{}", hosts);
                throw new ZkClientException("zookeeper connection timeout. host: " + hosts);
            }
        } catch (InterruptedException e) {
            throw new ZkClientException("connection lock exceprion.", e);
        }
    }


    /**
     * 重连zookeeper
     *
     * @throws ZkClientException
     */
    public void reconnection() throws ZkClientException {
        this.connection();
        this.process.relisten();
    }

    /**
     * 关闭客户端
     *
     * @throws ZkClientException
     */
    public void close() throws ZkClientException {
        try {
            if (zk != null && zk.getState().isConnected()) {
                zk.close();
            }
        } catch (InterruptedException e) {
            throw new ZkClientException("close zookeeper client error.", e);
        }
    }

    /**
     * 判断节点是否存在
     *
     * @param path
     * @return
     * @throws ZkClientException
     */
    public boolean exists(String path) throws ZkClientException {
        this.checkStatus();
        try {
            return this.zk.exists(path, false) != null;
        } catch (Exception e) {
            throw new ZkClientException("exists node " + path, e);
        }
    }

    /**
     * 检查zookeeper是否处于连接状态
     *
     * @return
     */
    public boolean checkConnection() {
        boolean conn = false;
        if (zk != null) {
            conn = zk.getState().isConnected();
        }
        return conn && this.isConnection();
    }


    /**
     * 检查zookeeper连接状态
     *
     * @return
     * @throws ZkClientException
     */
    public boolean checkStatus() throws ZkClientException {
        if (zk == null) {
            throw new ZkClientException("Not connected to the zookeeper server,host=" + hosts + ",invoking this.connect().");
        }
        if (zk.getState().isAlive() && this.isConnection()) {
            return true;
        }
        throw new ZkClientException("Not connected to the zookeeper server,host=" + hosts + ",state: " + zk.getState());
    }


    public boolean isConnection() {
        return isConnection;
    }

    public void setConnection(boolean connection) {
        isConnection = connection;
    }

    /**
     * 获取原生的zookeeper客户端对象
     *
     * @return
     */
    public ZooKeeper getZookeeper() {
        return zk;
    }
}
