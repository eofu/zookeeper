package com.myself.rmi.server;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface Service extends Remote {
	String hello(String str) throws RemoteException;
}
