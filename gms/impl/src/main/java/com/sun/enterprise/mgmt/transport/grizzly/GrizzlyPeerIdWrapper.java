package com.sun.enterprise.mgmt.transport.grizzly;

import com.sun.enterprise.ee.cms.impl.base.PeerID;

public class GrizzlyPeerIdWrapper extends PeerID<GrizzlyPeerID> {
	private static final long serialVersionUID = 5020656376989421847L;

	public GrizzlyPeerIdWrapper(GrizzlyPeerID uniqueID, String groupName,
			String instanceName) {
		super(uniqueID, groupName, instanceName);
	}

}
