package org.jivesoftware.openfire.auth;

import org.jivesoftware.openfire.user.UserAlreadyExistsException;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;

public class SkynetAuthProvider extends DefaultAuthProvider {

	@Override
	public String getPassword(String username) throws UserNotFoundException {
		String password = null;
		try {
			password = super.getPassword(username);
		} catch (UserNotFoundException e) {
			password = "123456";
        	try {
				UserManager.getUserProvider().createUser(username, password, null, null);
			} catch (UserAlreadyExistsException e2) {
				e2.printStackTrace();
			}
        	return password;
		}
		
		return password;
	}

}
