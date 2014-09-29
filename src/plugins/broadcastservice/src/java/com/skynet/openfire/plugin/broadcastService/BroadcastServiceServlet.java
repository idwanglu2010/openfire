package com.skynet.openfire.plugin.broadcastService;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jivesoftware.admin.AuthCheckFilter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.util.Log;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import com.skynet.openfire.plugin.BroadcastServicePlugin;

public class BroadcastServiceServlet extends HttpServlet {

	private static final String SERVICE_NAME = "broadcastService/broadcastservice";
	private BroadcastServicePlugin plugin;
	private String serverName;
	private JID serverAddress;

	public BroadcastServiceServlet() {
		serverName = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
		serverAddress = new JID(serverName);
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		plugin = (BroadcastServicePlugin) XMPPServer.getInstance().getPluginManager().getPlugin("broadcastservice");
		// Exclude this servlet from requiring the user to login
		AuthCheckFilter.addExclude(SERVICE_NAME);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		System.out.println("BroadcastServiceServlet doGet!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

		// Printwriter for writing out responses to browser
		PrintWriter out = response.getWriter();

		String secret = request.getParameter("secret");
		String jid = request.getParameter("jid");
		String body = request.getParameter("body");
		String type = request.getParameter("type");
		String from = request.getParameter("from");
		// Check that our plugin is enabled.
		if (!plugin.isEnabled()) {
			Log.warn("Broadcast service plugin is disabled: " + request.getQueryString());
			replyError("BroadcastServiceDisabled", response, out);
			return;
		}
		// Check this request is authorised
		if (secret == null || !secret.equals(plugin.getSecret())) {
			Log.warn("An unauthorised broadcast service request was received: " + request.getQueryString());
			replyError("RequestNotAuthorised", response, out);
			return;
		}
		// Some checking is required on the jid
		if (jid == null) {
			Log.warn("jidIsNullException: " + request.getQueryString());
			replyError("jidIsNullException", response, out);
			return;
		}// Some checking is required on the body
		if (body == null) {
			Log.warn("bodyIsNullException: " + request.getQueryString());
			replyError("bodyIsNullException", response, out);
			return;
		}
		// Some checking is required on the type
		if (type == null || !plugin.isSupportType(type)) {
			if (type == null) {
				Log.warn("typeIsNullException: " + request.getQueryString());
				replyError("typeIsNullException", response, out);
			} else {
				Log.warn("IllegalArgumentException, type : " + type + " is not support" + request.getQueryString());
				replyError("IllegalArgumentException, type : " + type + " is not support", response, out);
			}
			return;
		}
		try {
			Message m = createServerMessage(jid, null, body);
			if (from != null) {
				if (from.contains("@") && !from.startsWith("@") && !from.endsWith("@")) {
					JID temp = new JID(from);
					m.setFrom(temp);
				}
			}
			plugin.serverBroadcast(m, type);
		} catch (Throwable e) {
			replyError(e.toString(), response, out);
		}

	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
		System.out.println("BroadcastServiceServlet doPost!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
	}

	@Override
	public void destroy() {
		super.destroy();
		// Release the excluded URL
		AuthCheckFilter.removeExclude(SERVICE_NAME);
	}

	private void replyMessage(String message, HttpServletResponse response, PrintWriter out) {
		response.setContentType("text/xml");
		out.println("<result>" + message + "</result>");
		out.flush();
	}

	private void replyError(String error, HttpServletResponse response, PrintWriter out) {
		response.setContentType("text/xml");
		out.println("<error>" + error + "</error>");
		out.flush();
	}

	private Message createServerMessage(String to, String subject, String body) {
		Message message = new Message();
		message.setTo(to);
		message.setFrom(serverAddress);
		if (subject != null) {
			message.setSubject(subject);
		}
		message.setBody(body);
		return message;
	}
}
