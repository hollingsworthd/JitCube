package com.machinepublishers.neuraltrader;

import java.rmi.RemoteException;

public class ServerImpl implements Server {

  @Override
  public void upload(String key, NeuralNet cur) throws RemoteException {
    if (Main.getKey().equals(key)) {
      cur.save();
    }
  }

  @Override
  public NeuralNet download(String key, int indexFrom, int indexTo) throws RemoteException {
    return Main.getKey().equals(key) ? NeuralNet.create(indexFrom, indexTo) : null;
  }
}
