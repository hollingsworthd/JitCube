package com.machinepublishers.neuraltrader;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface Server extends Remote {

  void upload(String key, NeuralNet cur, NeuralNet prev) throws RemoteException;

  NeuralNet download(String key, int indexFrom, int indexTo) throws RemoteException;
}
