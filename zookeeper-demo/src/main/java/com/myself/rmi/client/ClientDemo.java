package com.myself.rmi.client;

import com.myself.rmi.server.Service;

import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;

public class ClientDemo {
	public static void main(String[] args) throws MalformedURLException, NotBoundException, RemoteException {
		Service service = (Service)Naming.lookup("rmi://127.0.0.1/hello");
		System.out.println(service.hello("ClientDemo"));
	}
}
