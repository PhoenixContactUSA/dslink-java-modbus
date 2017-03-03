package modbus;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.StringUtils;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;

abstract public class ModbusConnection {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(ModbusConnection.class);
	}

	static final String ATTR_SLAVE_NAME = "name";
	static final String ATTR_SLAVE_ID = "slave id";
	static final String ATTR_POLLING_INTERVAL = "polling interval";
	static final String ATTR_ZERO_ON_FAILED_POLL = "zero on failed poll";
	static final String ATTR_USE_BATCH_POLLING = "use batch polling";
	static final String ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY = "contiguous batch requests only";

	static final String ATTR_CONNECTION_NAME = "name";
	static final String ATTR_TRANSPORT_TYPE = "transport type";
	static final String ATTR_TIMEOUT = "Timeout";
	static final String ATTR_RETRIES = "retries";
	static final String ATTR_MAX_READ_BIT_COUNT = "max read bit count";
	static final String ATTR_MAX_READ_REGISTER_COUNT = "max read register count";
	static final String ATTR_MAX_WRITE_REGISTER_COUNT = "max write register count";
	static final String ATTR_DISCARD_DATA_DELAY = "discard data delay";
	static final String ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY = "use multiple write commands only";

	static final String ATTR_RESTORE_TYPE = "restoreType";
	static final String ATTR_RESTORE_CONNECITON = "conn";

	static final String NODE_STATUS = "Connection Status";
	static final String NODE_STATUS_SETTINGUP = "Setting up connection";
	static final String NODE_STATUS_CONNECTED = "Connected";
	static final String NODE_STATUS_CONNECTING = "connecting to device";

	static final String NODE_STATUS_CONNECTION_ESTABLISHMENT_FAILED = "Could not establish connection";
	static final String NODE_STATUS_CONNECTION_STOPPED = "Stopped";

	static final String ACTION_RESTART = "restart";
	static final String ACTION_STOP = "stop";
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";

	static final int RETRY_DELAY_MAX = 60;
	static final int RETRY_DELAY_STEP = 2;

	Node node;
	Node statnode;
	ModbusLink link;
	ModbusMaster master;
	final Object masterLock = new Object();
	Set<SlaveNode> slaves;
	ScheduledFuture<?> reconnectFuture = null;
	String name;
	protected int retryDelay = 1;

	int timeout;

	int retries;
	int maxrbc;
	int maxrrc;
	int maxwrc;
	int ddd;
	boolean mwo;

	final ScheduledThreadPoolExecutor stpe = Objects.createDaemonThreadPool();
	final ModbusFactory modbusFactory;

	public ModbusConnection(ModbusLink link, Node node) {
		this.link = link;
		this.node = node;

		modbusFactory = new ModbusFactory();
		this.statnode = node.createChild(NODE_STATUS, true).setValueType(ValueType.STRING)
				.setValue(new Value(NODE_STATUS_SETTINGUP)).build();
		slaves = new HashSet<>();
		node.setAttribute(ATTR_RESTORE_TYPE, new Value("conn"));
		link.connections.add(this);
	}

	void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject nodeobj = jobj.get(node.getName());
		jobj.put(StringUtils.encodeName(name), nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name, true);

		duplicate(link, newnode);
	}

	void rename(String newname) {
		duplicate(newname);
		remove();
	}

	void remove() {
		stop();

		node.clearChildren();
		node.getParent().removeChild(node);
		link.connections.remove(this);
	}

	void stop() {
		synchronized(masterLock) {
			if (master != null) {
				try {
					LOGGER.info(node.getName() + ": (stop) destroying master");
					master.destroy();
					LOGGER.info(node.getName() + ": (stop) destroyed master");
					link.masters.remove(master);
				} catch (Exception e) {
					LOGGER.info(node.getName() + ": (stop) error destroying master");
					LOGGER.debug("error destroying last master" + e.getMessage());
				}
				master = null;
				removeChild();
			}
		}
		statnode.setValue(new Value(NODE_STATUS_CONNECTION_STOPPED));
	}

	void restoreLastSession() {
		init();

		slaves.clear();

		if (node.getChildren() == null)
			return;

		Map<String, Node> children = node.getChildren();
		for (Node child : children.values()) {
			Value slaveId = child.getAttribute(ATTR_SLAVE_ID);
			Value interval = child.getAttribute(ATTR_POLLING_INTERVAL);
			Value zerofail = child.getAttribute(ATTR_ZERO_ON_FAILED_POLL);
			if (zerofail == null) {
				child.setAttribute(ATTR_ZERO_ON_FAILED_POLL, new Value(false));
			}
			Value batchpoll = child.getAttribute(ATTR_USE_BATCH_POLLING);
			if (batchpoll == null) {
				child.setAttribute(ATTR_USE_BATCH_POLLING, new Value(true));
			}
			Value contig = child.getAttribute(ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY);
			if (contig == null) {
				child.setAttribute(ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(true));
			}
			if (slaveId != null && interval != null) {
				SlaveNode sn = new SlaveNode(this, child);
				sn.restoreLastSession();
			} else if (child.getAction() == null && !NODE_STATUS.equals(child.getName())) {
				node.removeChild(child);
			}
		}
	}

	void init() {
		Action act = getRemoveAction();

		Node anode = node.getChild(ACTION_REMOVE, true);
		if (anode == null) {
			node.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
		act = getEditAction();
		anode = node.getChild(ACTION_EDIT, true);
		if (anode == null) {
			anode = node.createChild(ACTION_EDIT, true).setAction(act).build();
			anode.setSerializable(false);
		} else {
			anode.setAction(act);
		}

		act = new Action(Permission.READ, new RestartHandler());
		anode = node.getChild(ACTION_RESTART, true);
		if (anode == null) {
			node.createChild(ACTION_RESTART, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}

		makeStopAction();
		
		synchronized(masterLock) {
			master = getMaster();
			if (master != null) {
				statnode.setValue(new Value(NODE_STATUS_CONNECTED));
				retryDelay = 1;
				act = getAddDeviceAction();
				anode = node.getChild(getAddDeviceActionName(), true);
				if (anode == null) {
					node.createChild(getAddDeviceActionName(), true).setAction(act).build().setSerializable(false);
				} else {
					anode.setAction(act);
				}
			} else {
				statnode.setValue(new Value(NODE_STATUS_CONNECTION_ESTABLISHMENT_FAILED));
				removeChild();
				scheduleReconnect();
			}
		}
	}

	class RestartHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			if (reconnectFuture != null) {
				reconnectFuture.cancel(false);
			}
			stop();
			restoreLastSession();
		}
	}

	public ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return stpe;
	}

	public Action getRemoveAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				remove();
			}
		});

		return act;
	}

	ModbusLink getLink() {
		return this.link;
	}

	Action getAddDeviceAction() {
		Action act = new Action(Permission.READ, getAddDeviceActionHandler());
		act.addParameter(new Parameter(ATTR_SLAVE_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ATTR_SLAVE_ID, ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter(ATTR_POLLING_INTERVAL, ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter(ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter(ATTR_USE_BATCH_POLLING, ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter(ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL, new Value(false)));

		return act;
	}

	void makeStopAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				if (reconnectFuture != null) {
					reconnectFuture.cancel(false);
				}
				stop();
			}
		});

		Node anode = node.getChild("stop", true);
		if (anode == null)
			node.createChild("stop", true).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	void checkConnection() {
		synchronized(masterLock) {
			LOGGER.info(node.getName() + ": checking connection");
			boolean connected = (master != null) && master.isInitialized() && master.isConnected();
			LOGGER.info(node.getName() + ": is connected: " + connected);
	
			if (!connected) {
				statnode.setValue(new Value(NODE_STATUS_CONNECTING));
				if (master != null) {
					try {
						LOGGER.info(node.getName() + ": (checkConnection) destroying master");
						master.destroy();
						LOGGER.info(node.getName() + ": (checkConnection) destroyed master");
						link.masters.remove(master);
					} catch (Exception e) {	
						LOGGER.info(node.getName() + ": (checkConnection) error destroying master");
					}
				}
				master = null;
				LOGGER.info(node.getName() + ": not connected, calling scheduleReconnect");
				scheduleReconnect();
			} else {
				LOGGER.info(node.getName() + ": appears to be connected");
				statnode.setValue(new Value(NODE_STATUS_CONNECTED));
			}
		}
	}

	synchronized void scheduleReconnect() {
		if (link.restoring || (reconnectFuture != null && !reconnectFuture.isDone())) {
			return;
		}
		LOGGER.info(node.getName() + ": scheduling reconnect with delay " + retryDelay);
		ScheduledThreadPoolExecutor reconnectStpe = Objects.getDaemonThreadPool();
		reconnectFuture = reconnectStpe.schedule(new Runnable() {

			@Override
			public void run() {
				Value stat = statnode.getValue();
				LOGGER.info(node.getName() + ": checking status before attempting reconnect");
				if (stat == null || !(NODE_STATUS_CONNECTED.equals(stat.getString())
						|| NODE_STATUS_SETTINGUP.equals(stat.getString()))) {
					LOGGER.info(node.getName() + ": attempting reconnect");
					stop();
					restoreLastSession();
				}
			}
		}, retryDelay, TimeUnit.SECONDS);
		if (retryDelay < RETRY_DELAY_MAX)
			retryDelay += RETRY_DELAY_STEP;
		LOGGER.info(node.getName() + ": scheduled reconnect, next delay is " + retryDelay);
	}

	public void readMasterParameters(ActionResult event) {
		name = event.getParameter(ATTR_SLAVE_NAME, ValueType.STRING).getString();
		timeout = event.getParameter(ATTR_TIMEOUT, ValueType.NUMBER).getNumber().intValue();
		retries = event.getParameter(ATTR_RETRIES, ValueType.NUMBER).getNumber().intValue();
		maxrbc = event.getParameter(ATTR_MAX_READ_BIT_COUNT, ValueType.NUMBER).getNumber().intValue();
		maxrrc = event.getParameter(ATTR_MAX_READ_REGISTER_COUNT, ValueType.NUMBER).getNumber().intValue();
		maxwrc = event.getParameter(ATTR_MAX_WRITE_REGISTER_COUNT, ValueType.NUMBER).getNumber().intValue();
		ddd = event.getParameter(ATTR_DISCARD_DATA_DELAY, ValueType.NUMBER).getNumber().intValue();
		mwo = event.getParameter(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY, ValueType.BOOL).getBool();
	}

	public void writeMasterParameters() {
		master.setTimeout(timeout);
		master.setRetries(retries);
		master.setMaxReadBitCount(maxrbc);
		master.setMaxReadRegisterCount(maxrrc);
		master.setMaxWriteRegisterCount(maxwrc);
		master.setDiscardDataDelay(ddd);
		master.setMultipleWritesOnly(mwo);
	}

	public void writeMasterAttributes() {
		node.setAttribute(ATTR_TIMEOUT, new Value(timeout));
		node.setAttribute(ATTR_RETRIES, new Value(retries));
		node.setAttribute(ATTR_MAX_READ_BIT_COUNT, new Value(maxrbc));
		node.setAttribute(ATTR_MAX_READ_REGISTER_COUNT, new Value(maxrrc));
		node.setAttribute(ATTR_MAX_WRITE_REGISTER_COUNT, new Value(maxwrc));
		node.setAttribute(ATTR_DISCARD_DATA_DELAY, new Value(ddd));
		node.setAttribute(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY, new Value(mwo));
	}

	public void readMasterAttributes() {
		timeout = node.getAttribute(ATTR_TIMEOUT).getNumber().intValue();
		retries = node.getAttribute(ATTR_RETRIES).getNumber().intValue();
		maxrbc = node.getAttribute(ATTR_MAX_READ_BIT_COUNT).getNumber().intValue();
		maxrrc = node.getAttribute(ATTR_MAX_READ_REGISTER_COUNT).getNumber().intValue();
		maxwrc = node.getAttribute(ATTR_MAX_WRITE_REGISTER_COUNT).getNumber().intValue();
		ddd = node.getAttribute(ATTR_DISCARD_DATA_DELAY).getNumber().intValue();
		mwo = node.getAttribute(ATTR_USE_MULTIPLE_WRITE_COMMAND_ONLY).getBool();
	}

	public int getTimeout() {
		return timeout;
	}

	public int getDdd() {
		return ddd;
	}

	public int getRetries() {
		return retries;
	}

	public int getMaxrbc() {
		return maxrbc;
	}

	public int getMaxrrc() {
		return maxrrc;
	}

	public int getMaxwrc() {
		return maxwrc;
	}

	public boolean isMwo() {
		return mwo;
	}

	abstract void duplicate(ModbusLink link, Node node);

	abstract void removeChild();

	abstract ModbusMaster getMaster();

	abstract Handler<ActionResult> getAddDeviceActionHandler();

	abstract String getAddDeviceActionName();

	abstract Action getEditAction();
}
