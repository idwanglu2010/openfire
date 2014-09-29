package com.skynet.openfire.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.dom4j.Element;
import org.jivesoftware.openfire.PresenceManager;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.group.Group;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.muc.spi.LocalMUCRoom;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.jivesoftware.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.Component;
import org.xmpp.component.ComponentException;
import org.xmpp.component.ComponentManager;
import org.xmpp.component.ComponentManagerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

public class BroadcastServicePlugin implements Plugin, Component, PropertyEventListener {

	public enum BROADCAST_MESSAGE_TYPE {
		/* push消息给某个用户 */
		BROADCAST_TO_USER(0),
		/* push消息给某个聊天室 */
		BROADCAST_TO_ROOM(1),
		/* push消息给所有用户 */
		BROADCAST_TO_ALL_USERS(2),
		/* push消息给某个组 */
		BROADCAST_TO_GROUP(3);

		private BROADCAST_MESSAGE_TYPE(int type) {
			this.value = type;
		}

		@Override
		public String toString() {
			return String.valueOf(value);

		}

		private int value;
	};

	private static final Logger Log = LoggerFactory.getLogger(BroadcastServicePlugin.class);

	private XMPPServer server;
	private String secret;
	private boolean enabled;
	private List<JID> allowedUsers;

	private ComponentManager componentManager;
	private PluginManager pluginManager;
	private GroupManager groupManager;
	private SessionManager sessionManager;
	private UserManager userManager;
	PresenceManager presenceManager;
	private String serviceName;
	private boolean groupMembersAllowed;
	private boolean disableGroupPermissions;
	private boolean all2ofline;
	private String messagePrefix;

	public BroadcastServicePlugin() {
		serviceName = JiveGlobals.getProperty("plugin.broadcastservice.serviceName", "broadcastservice");
		disableGroupPermissions = JiveGlobals.getBooleanProperty("plugin.broadcastservice.disableGroupPermissions");
		groupMembersAllowed = JiveGlobals.getBooleanProperty("plugin.broadcastservice.groupMembersAllowed", true);
		allowedUsers = stringToList(JiveGlobals.getProperty("plugin.broadcastservice.allowedUsers", ""));
		all2ofline = JiveGlobals.getBooleanProperty("plugin.broadcastservice.all2offline", false);
		messagePrefix = JiveGlobals.getProperty("plugin.broadcastservice.messagePrefix", null);

	}

	@Override
	public void initializePlugin(PluginManager manager, File pluginDirectory) {
		System.out.println("initialize BroadcastServicePlugin");
		pluginManager = manager;
		server = XMPPServer.getInstance();
		sessionManager = SessionManager.getInstance();
		groupManager = GroupManager.getInstance();
		userManager = UserManager.getInstance();
		presenceManager = server.getPresenceManager();
		secret = JiveGlobals.getProperty("plugin.broadcastservice.secret", "");
		// If no secret key has been assigned to the broadcast service yet,
		// assign a
		// random one.
		if (secret.equals("")) {
			secret = StringUtils.randomString(8);
			setSecret(secret);
		}

		// See if the service is enabled or not.
		enabled = JiveGlobals.getBooleanProperty("plugin.broadcastservice.enabled", false);

		// Register as a component.
		componentManager = ComponentManagerFactory.getComponentManager();
		try {
			componentManager.addComponent(serviceName, this);
		} catch (Exception e) {
			Log.error(e.getMessage(), e);
		}
		// Listen to system property events
		PropertyEventDispatcher.addListener(this);
	}

	@Override
	public void destroyPlugin() {
		// Stop listening to system property events
		PropertyEventDispatcher.removeListener(this);
		// Unregister component.
		if (componentManager != null) {
			try {
				componentManager.removeComponent(serviceName);
			} catch (Exception e) {
				Log.error(e.getMessage(), e);
			}
		}
		componentManager = null;
	}

	@Override
	public void propertyDeleted(String property, Map<String, Object> params) {
		if (property.equals("plugin.broadcastservice.secret")) {
			this.secret = "";
		} else if (property.equals("plugin.broadcastservice.enabled")) {
			this.enabled = false;
		}
	}

	@Override
	public void propertySet(String property, Map<String, Object> params) {
		if (property.equals("plugin.broadcastservice.secret")) {
			this.secret = (String) params.get("value");
		} else if (property.equals("plugin.broadcastservice.enabled")) {
			this.enabled = Boolean.parseBoolean((String) params.get("value"));
		}
	}

	@Override
	public void xmlPropertyDeleted(String arg0, Map<String, Object> arg1) {
		// TODO Auto-generated method stub

	}

	@Override
	public void xmlPropertySet(String arg0, Map<String, Object> arg1) {
		// TODO Auto-generated method stub

	}

	public PluginManager getPluginManager() {
		return pluginManager;
	}

	/**
	 * Returns the secret key that only valid requests should know.
	 * 
	 * @return the secret key.
	 */
	public String getSecret() {
		return secret;
	}

	/**
	 * Sets the secret key that grants permission to use the broadcastservice.
	 * 
	 * @param secret
	 *            the secret key.
	 */
	public void setSecret(String secret) {
		JiveGlobals.setProperty("plugin.broadcastservice.secret", secret);
		this.secret = secret;
	}

	/**
	 * Enables or disables the broadcast service. If not enabled, it will not
	 * accept requests.
	 * 
	 * @param enabled
	 *            true if the broadcast service should be enabled.
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		JiveGlobals.setProperty("plugin.broadcastservice.enabled", enabled ? "true" : "false");
	}

	/**
	 * Returns true if the broadcast service is enabled. If not enabled, it will
	 * not accept requests.
	 * 
	 * @return true if the broadcast service is enabled.
	 */
	public boolean isEnabled() {
		return enabled;
	}

	// Component Interface

	@Override
	public String getDescription() {
		// Get the description from the plugin.xml file.
		return pluginManager.getDescription(this);
	}

	@Override
	public String getName() {
		// Get the name from the plugin.xml file.
		return pluginManager.getName(this);
	}

	public void serverBroadcast(Packet packet, String type) {
		if (type == null)
			return;
		String toNode = packet.getTo().getNode();
		if (type.equals(BROADCAST_MESSAGE_TYPE.BROADCAST_TO_USER.toString())) {
			try {
				User user = userManager.getUser(toNode);
				if (presenceManager.isAvailable(user)) {
					JID toUserJid = presenceManager.getPresence(user).getFrom();
					sessionManager.sendServerMessage(toUserJid, null, "test");
				} else {
					JID toUserJid = packet.getTo();
					Message msg = (Message) packet;
					server.getOfflineMessageStrategy().storeOffline(msg);
				}
			} catch (UserNotFoundException e) {
				e.printStackTrace();
			}
		} else if (type.equals(BROADCAST_MESSAGE_TYPE.BROADCAST_TO_ROOM.toString())) {

			MultiUserChatManager m = server.getMultiUserChatManager();
			MultiUserChatService s = m.getMultiUserChatService(packet.getTo());
			if (null != s) {
				LocalMUCRoom room = (LocalMUCRoom) s.getChatRoom(toNode);
				if (null != room) {
					Message msg = (Message) packet;
					room.serverBroadcast(msg.getBody());
				}
			}

		} else if (type.equals(BROADCAST_MESSAGE_TYPE.BROADCAST_TO_ALL_USERS.toString())) {
			processPacket(packet);
		} else if (type.equals(BROADCAST_MESSAGE_TYPE.BROADCAST_TO_GROUP.toString())) {
			processPacket(packet);
		}

	}

	@Override
	public void processPacket(Packet packet) {
		boolean canProceed = false;
		Group group = null;
		String toNode = packet.getTo().getNode();
		// Check if user is allowed to send packet to this service[+group]
		boolean targetAll = "all".equals(toNode);
		if (targetAll) {
			// See if the user is allowed to send the packet.
			JID address = new JID(packet.getFrom().toBareJID());
			if (allowedUsers.isEmpty() || allowedUsers.contains(address)) {
				canProceed = true;
			}
		} else {
			try {
				if (toNode != null) {
					group = groupManager.getGroup(toNode);
					boolean isGroupUser = group.isUser(packet.getFrom()) || group.isUser(new JID(packet.getFrom().toBareJID()));
					if (disableGroupPermissions || (groupMembersAllowed && isGroupUser) || allowedUsers.contains(new JID(packet.getFrom().toBareJID()))) {
						canProceed = true;
					}
				}
			} catch (GroupNotFoundException e) {
				// Ignore.
			}
		}
		if (packet instanceof Message) {
			// Respond to incoming messages
			Message message = (Message) packet;
			processMessage(message, targetAll, group, canProceed);
		} else if (packet instanceof Presence) {
			// Respond to presence subscription request or presence probe
			Presence presence = (Presence) packet;
			processPresence(canProceed, presence);
		} else if (packet instanceof IQ) {
			// Handle disco packets
			IQ iq = (IQ) packet;
			// Ignore IQs of type ERROR or RESULT
			if (IQ.Type.error == iq.getType() || IQ.Type.result == iq.getType()) {
				return;
			}
			processIQ(iq, targetAll, group, canProceed);
		}

	}

	@Override
	public void initialize(JID jid, ComponentManager componentManager) throws ComponentException {
		// TODO Auto-generated method stub

	}

	@Override
	public void shutdown() {
		// TODO Auto-generated method stub

	}

	@Override
	public void start() {
		// TODO Auto-generated method stub

	}

	private void processMessage(Message message, boolean targetAll, Group group, boolean canProceed) {
		// Check to see if trying to broadcast to all connected users.
		if (targetAll) {
			if (!canProceed) {
				Message error = new Message();
				if (message.getID() != null) {
					error.setID(message.getID());
				}
				error.setError(PacketError.Condition.not_allowed);
				error.setTo(message.getFrom());
				error.setFrom(message.getTo());
				error.setSubject("Error sending broadcast message");
				error.setBody("Not allowed to send a broadcast message to " + message.getTo());
				try {
					componentManager.sendPacket(this, error);
				} catch (Exception e) {
					Log.error(e.getMessage(), e);
				}
				return;
			}

			if ((messagePrefix != null) && (message.getBody() != null)) {
				message.setBody(messagePrefix + " " + message.getBody());
			}

			if (all2ofline == false) {
				// send to online users
				sessionManager.broadcast(message);
			} else {
				// send to all users
				Collection<User> users = userManager.getUsers();
				String xmppdomain = "@" + JiveGlobals.getProperty("xmpp.domain");
				for (User u : users) {
					Message newMessage = message.createCopy();
					newMessage.setTo(u.getUsername() + xmppdomain);
					try {
						componentManager.sendPacket(this, newMessage);
					} catch (Exception e) {
						Log.error(e.getMessage(), e);
					}
				}
			}
		}
		// See if the name is a group.
		else {
			if (group == null) {
				// The address is not recognized so send an error message back.
				Message error = new Message();
				if (message.getID() != null) {
					error.setID(message.getID());
				}
				error.setTo(message.getFrom());
				error.setFrom(message.getTo());
				error.setError(PacketError.Condition.not_allowed);
				error.setSubject("Error sending broadcast message");
				error.setBody("Address not valid: " + message.getTo());
				try {
					componentManager.sendPacket(this, error);
				} catch (Exception e) {
					Log.error(e.getMessage(), e);
				}
			} else if (canProceed) {
				// Broadcast message to group users. Users that are offline will
				// get
				// the message when they come back online
				if ((messagePrefix != null) && (message.getBody() != null)) {
					message.setBody(messagePrefix + " " + message.getBody());
				}
				for (JID userJID : group.getMembers()) {
					Message newMessage = message.createCopy();
					newMessage.setTo(userJID);
					try {
						componentManager.sendPacket(this, newMessage);
					} catch (Exception e) {
						Log.error(e.getMessage(), e);
					}
				}
			} else {
				// Otherwise, the address is recognized so send an error message
				// back.
				Message error = new Message();
				if (message.getID() != null) {
					error.setID(message.getID());
				}
				error.setTo(message.getFrom());
				error.setFrom(message.getTo());
				error.setError(PacketError.Condition.not_allowed);
				error.setSubject("Error sending broadcast message");
				error.setBody("Not allowed to send a broadcast message to " + message.getTo());
				try {
					componentManager.sendPacket(this, error);
				} catch (Exception e) {
					Log.error(e.getMessage(), e);
				}
			}
		}
	}

	private void processPresence(boolean canProceed, Presence presence) {
		try {
			if (Presence.Type.subscribe == presence.getType()) {
				// Accept all presence requests if user has permissions
				// Reply that the subscription request was approved or rejected
				Presence reply = new Presence();
				reply.setTo(presence.getFrom());
				reply.setFrom(presence.getTo());
				reply.setType(canProceed ? Presence.Type.subscribed : Presence.Type.unsubscribed);
				componentManager.sendPacket(this, reply);
			} else if (Presence.Type.unsubscribe == presence.getType()) {
				// Send confirmation of unsubscription
				Presence reply = new Presence();
				reply.setTo(presence.getFrom());
				reply.setFrom(presence.getTo());
				reply.setType(Presence.Type.unsubscribed);
				componentManager.sendPacket(this, reply);
				if (!canProceed) {
					// Send unavailable presence of the service
					reply = new Presence();
					reply.setTo(presence.getFrom());
					reply.setFrom(presence.getTo());
					reply.setType(Presence.Type.unavailable);
					componentManager.sendPacket(this, reply);
				}
			} else if (Presence.Type.probe == presence.getType()) {
				// Send that the service is available
				Presence reply = new Presence();
				reply.setTo(presence.getFrom());
				reply.setFrom(presence.getTo());
				if (!canProceed) {
					// Send forbidden error since user is not allowed
					reply.setError(PacketError.Condition.forbidden);
				}
				componentManager.sendPacket(this, reply);
			}
		} catch (ComponentException e) {
			Log.error(e.getMessage(), e);
		}
	}

	private void processIQ(IQ iq, boolean targetAll, Group group, boolean canProceed) {
		IQ reply = IQ.createResultIQ(iq);
		Element childElement = iq.getChildElement();
		String namespace = childElement.getNamespaceURI();
		Element childElementCopy = iq.getChildElement().createCopy();
		reply.setChildElement(childElementCopy);
		if ("http://jabber.org/protocol/disco#info".equals(namespace)) {
			if (iq.getTo().getNode() == null) {
				// Return service identity and features
				Element identity = childElementCopy.addElement("identity");
				identity.addAttribute("category", "component");
				identity.addAttribute("type", "generic");
				identity.addAttribute("name", "Broadcast service");
				childElementCopy.addElement("feature").addAttribute("var", "http://jabber.org/protocol/disco#info");
				childElementCopy.addElement("feature").addAttribute("var", "http://jabber.org/protocol/disco#items");
			} else {
				if (targetAll) {
					// Return identity and features of the "all" group
					Element identity = childElementCopy.addElement("identity");
					identity.addAttribute("category", "component");
					identity.addAttribute("type", "generic");
					identity.addAttribute("name", "Broadcast all connected users");
					childElementCopy.addElement("feature").addAttribute("var", "http://jabber.org/protocol/disco#info");
				} else if (group != null && canProceed) {
					// Return identity and features of the "all" group
					Element identity = childElementCopy.addElement("identity");
					identity.addAttribute("category", "component");
					identity.addAttribute("type", "generic");
					identity.addAttribute("name", "Broadcast " + group.getName());
					childElementCopy.addElement("feature").addAttribute("var", "http://jabber.org/protocol/disco#info");
				} else {
					// Group not found or not allowed to use that group so
					// answer item_not_found error
					reply.setError(PacketError.Condition.item_not_found);
				}
			}
		} else if ("http://jabber.org/protocol/disco#items".equals(namespace)) {
			if (iq.getTo().getNode() == null) {
				// Return the list of groups hosted by the service that can be
				// used by the user
				Collection<Group> groups;
				JID address = new JID(iq.getFrom().toBareJID());
				if (allowedUsers.contains(address)) {
					groups = groupManager.getGroups();
				} else {
					groups = groupManager.getGroups(iq.getFrom());
				}
				for (Group userGroup : groups) {
					try {
						JID groupJID = new JID(userGroup.getName() + "@" + serviceName + "." + componentManager.getServerName());
						childElementCopy.addElement("item").addAttribute("jid", groupJID.toString());
					} catch (Exception e) {
						// Group name is not valid to be used as a JID
					}
				}
				if (allowedUsers.isEmpty() || allowedUsers.contains(address)) {
					// Add the "all" group to the list
					childElementCopy.addElement("item").addAttribute("jid", "all@" + serviceName + "." + componentManager.getServerName());
				}
			}
		} else {
			// Answer an error since the server can't handle the requested
			// namespace
			reply.setError(PacketError.Condition.service_unavailable);
		}
		try {
			componentManager.sendPacket(this, reply);
		} catch (Exception e) {
			Log.error(e.getMessage(), e);
		}
	}

	/**
	 * Returns a comma-delimitted list of strings into a Collection of Strings.
	 * 
	 * @param str
	 *            the String.
	 * @return a list.
	 */
	private List<JID> stringToList(String str) {
		List<JID> values = new ArrayList<JID>();
		StringTokenizer tokens = new StringTokenizer(str, ",");
		while (tokens.hasMoreTokens()) {
			String value = tokens.nextToken().trim();
			if (!value.equals("")) {
				// See if this is a full JID or just a username.
				if (value.contains("@")) {
					values.add(new JID(value));
				} else {
					values.add(XMPPServer.getInstance().createJID(value, null));
				}
			}
		}
		return values;
	}

	public boolean isSupportType(String type) {
		boolean flag = false;
		if (type == null) {
			return false;
		}
		if (type.equals(BROADCAST_MESSAGE_TYPE.BROADCAST_TO_USER.toString()) || type.equals(BROADCAST_MESSAGE_TYPE.BROADCAST_TO_ROOM.toString())
				|| type.equals(BROADCAST_MESSAGE_TYPE.BROADCAST_TO_ALL_USERS.toString()) || type.equals(BROADCAST_MESSAGE_TYPE.BROADCAST_TO_GROUP.toString())) {
			flag = true;
		}
		return flag;
	}
}
