package com.myself.rmi.server;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

public class ServiceImpl extends UnicastRemoteObject implements Service {
	protected ServiceImpl() throws RemoteException {
		super();
	}

	@Override
	public String hello(String str) {
		return "Hello:" + str;
	}
}
