package com.civism.zookeeper.watcher;


import com.civism.zookeeper.ZkClient;
import com.civism.zookeeper.ZkClientException;
import com.civism.zookeeper.listener.*;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author star
 * @date 2018/8/3 下午1:45
 */
public class WatcherProcess {

    private final static Logger LOGGER = LoggerFactory.getLogger(WatcherProcess.class);
    private ZkClient zkClient;
    /**
     * 节点监听池
     */
    private final ConcurrentHashMap<String, ListenerManager> nodeListenerPool = new ConcurrentHashMap<>();
    /**
     * 数据监听池
     */
    private final ConcurrentHashMap<String, ListenerManager> dataListenerPool = new ConcurrentHashMap<>();
    /**
     * 坚固的节点（顽固），服务重链后节点自动注册
     */
    private final ConcurrentHashMap<String, Node> stubbornNodePool = new ConcurrentHashMap<>();
    /**
     * 客户端状态监听池
     */
    private final ConcurrentHashMap<Integer, StateListener> statePool = new ConcurrentHashMap<>();
    private ListenerProcessPool listenerPool = null;

    /**
     * @param zkClient         ZkClinet对象用于操作zookeeper
     * @param listenerPoolSize zookeeper事件触发后的回调执行线程池大小
     */
    public WatcherProcess(ZkClient zkClient, int listenerPoolSize) {
        this.zkClient = zkClient;
        listenerPool = new ListenerProcessPool(listenerPoolSize);
    }

    /**
     * watch事件处理类
     * 设置处理监听事件线程数为2
     *
     * @param zkClient
     */
    public WatcherProcess(ZkClient zkClient) {
        this(zkClient, 2);
    }

    /**
     * 设置监听对象,监听节点变化，当监听的事件发生时将回调listen()方法
     *
     * @param path
     * @param ChildNodeChange ture 为监听子节点变化，false为监听本节点数据变化
     * @throws org.apache.zookeeper.KeeperException
     * @throws InterruptedException
     */
    public void listen(String path, Listener listener, boolean ChildNodeChange, boolean childDataChange) throws ZkClientException {
        try {
            ListenerManager manager = new ListenerManager(listener, childDataChange, ChildNodeChange);
            if (ChildNodeChange || childDataChange) {
                nodeListenerPool.put(path, manager);
                this.childChange(path, true);
            } else {
                dataListenerPool.put(path, manager);
                this.dataChange(path);
            }
        } catch (Exception e) {
            throw new ZkClientException("Listen node " + path, e);
        }
    }

    /**
     * 取消节点监听
     *
     * @param path      节点地址
     * @param child     true表示监听子节点变化，false表示监听节点数据变化
     * @param childData 子节点数据变化
     */
    public void unlisten(String path, boolean child, boolean childData) throws ZkClientException {
        if (child || childData) {
            if (zkClient.exists(path)) {
                List<String> nodes = this.zkClient.getChild(path, false);
                if (childData) {
                    for (String node : nodes) {
                        String childNode = path + "/" + node;
                        dataListenerPool.remove(childNode);
                        this.zkClient.getData(childNode, false);
                    }
                }
            }
            nodeListenerPool.remove(path);
        } else {
            if (zkClient.exists(path)) {
                this.zkClient.getData(path, false);
            }
            dataListenerPool.remove(path);
        }
    }

    /**
     * 当session超时重连后，重新注册监听事件
     */
    public void relisten() throws ZkClientException {
        for (Map.Entry<String, ListenerManager> entry : dataListenerPool.entrySet()) {
            this.dataChange(entry.getKey());
            LOGGER.debug("Relisten data node:{}", entry.getKey());
        }
        for (Map.Entry<String, ListenerManager> entry : nodeListenerPool.entrySet()) {
            this.childChange(entry.getKey(), false);
            LOGGER.debug("Relisten child node:{}", entry.getKey());
        }
        for (Map.Entry<String, Node> entry : stubbornNodePool.entrySet()) {
            Node node = entry.getValue();
            this.zkClient.create(node.getPath(), node.getData(), false);
            LOGGER.debug("Recreate (stubborn node) node:{}", entry.getKey());
        }
    }

    /**
     * 设置状态变化监听器，当zookeeper状态发生变化时回调监听器
     *
     * @param state
     * @param listener
     */
    public void listenState(Watcher.Event.KeeperState state, StateListener listener) {
        this.statePool.put(state.getIntValue(), listener);
    }

    public void nulistenState(Watcher.Event.KeeperState state) {
        this.statePool.remove(state.getIntValue());
    }

    /**
     * 将发生的zookeeper状态变化进行回调
     *
     * @param state
     */
    public void listen(Watcher.Event.KeeperState state) {
        StateListener listener = statePool.get(state.getIntValue());
        if (listener != null) {
            listener.listen(state);
        }
    }

    /**
     * 节点数据变化处理函数
     *
     * @param path 变化的节点
     */
    public void dataChange(String path) throws ZkClientException {
        try {
            if (dataListenerPool.containsKey(path)) {
                byte[] data = this.zkClient.getData(path, true);
                ListenerManager manager = dataListenerPool.get(path);
                ListenerManager lm = new ListenerManager(manager.getListener());
                lm.setData(data);
                lm.setEventType(Watcher.Event.EventType.NodeDataChanged);
                listenerPool.invoker(path, lm);
                LOGGER.debug("node:{} data change.", path);
            }
        } catch (Exception e) {
            throw new ZkClientException("Listener data change error.", e);
        }
    }

    /**
     * 子节点变化处理函数
     *
     * @param path 节点路径
     * @param init 是否是初次监听，第一次监听将阻塞返回结果
     */
    public void childChange(String path, boolean init) throws ZkClientException {
        if (nodeListenerPool.containsKey(path)) {
            try {
                List<String> changeNodes = this.zkClient.getChild(path, true);
                ListenerManager manager = nodeListenerPool.get(path);
                this.diff(path, changeNodes, manager, init);
            } catch (Exception e) {
                throw new ZkClientException("Listener client node change error.", e);
            }
        }
    }

    /**
     * 检查子节点变化
     *
     * @param changeList 变化后的子节点集合
     * @return
     */
    private void diff(String path, List<String> changeList, ListenerManager manager, boolean init) throws ZkClientException, SocketException {
        if (changeList == null) {
            changeList = new ArrayList<>();
        }
        Map<String, Boolean> changeMap = new HashMap<>(changeList.size());
        Map<String, Boolean> oldMap = manager.getChildNode();
        for (String node : changeList) {
            changeMap.put(node, true);
            Boolean status = oldMap.get(node);
            if (status == null) {
                oldMap.put(node, true);
                String cpath = path + "/" + node;
                if (manager.isChildChange() || manager.isChildDataChange()) {
                    ListenerManager lm = new ListenerManager(manager.getListener());
                    byte[] data = zkClient.getData(cpath, manager.isChildDataChange());
                    lm.setData(data);
                    lm.setEventType(Watcher.Event.EventType.NodeCreated);
                    if (!init) {
                        listenerPool.invoker(cpath, lm);
                    } else {
                        manager.getListener().listen(cpath, Watcher.Event.EventType.NodeCreated, data);
                    }
                }
                if (manager.isChildDataChange()) {
                    ListenerManager dataManager = new ListenerManager(manager.getListener(), false, false);
                    dataListenerPool.put(cpath, dataManager);
                }
                LOGGER.debug("node:{} child change,type:node-create", node);
            }
        }

        for (Map.Entry<String, Boolean> entry : oldMap.entrySet()) {
            if (!changeMap.containsKey(entry.getKey())) {
                oldMap.remove(entry.getKey());
                String cpath = path + "/" + entry.getKey();
                if (manager.isChildDataChange()) {
                    unlisten(cpath, false, false);
                }
                ListenerManager lm = new ListenerManager(manager.getListener());
                lm.setData(new byte[1]);
                lm.setEventType(Watcher.Event.EventType.NodeDeleted);
                listenerPool.invoker(cpath, lm);
                LOGGER.debug("node:{} child change,type:node-delete", entry.getKey());
            } else {
                oldMap.put(entry.getKey(), false);
            }
        }
    }

    /**
     * 创建一个顽固的临时节点，当会话断开时删除，重连后自动创建
     *
     * @param path
     * @param data
     * @throws ZkClientException
     */
    public void stubborn(String path, byte[] data) throws ZkClientException {
        if (path != null && data != null) {
            Node node = new Node();
            node.setPath(path);
            node.setData(data);
            stubbornNodePool.put(path, node);
            LOGGER.debug("Stubborn node create success,node:{}", node);
        } else {
            throw new ZkClientException("Create node error,node = null or data = null.");
        }

    }
}
