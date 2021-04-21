package com.myself.serial;

import java.io.Serializable;

public class Person implements Cloneable, Serializable {
	private String name;
	private Email email;

	public Email getEmail() {
		return email;
	}

	public void setEmail(Email email) {
		this.email = email;
	}

	public Person(String name) {
		this.name = name;
	}

	@Override
	protected Person clone() throws CloneNotSupportedException {
		return (Person)super.clone();
	}

	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return "Person{" +
				"name='" + name + '\'' +
				", email=" + email +
				'}';
	}
}
