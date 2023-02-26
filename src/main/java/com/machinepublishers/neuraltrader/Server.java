package com.machinepublishers.neuraltrader;

import com.machinepublishers.neuraltrader.Prices.Marker;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface Server extends Remote {

  void upload(String key, NeuralNet cur) throws RemoteException;

  NeuralNet download(String key, int indexFrom, int indexTo) throws RemoteException;

  Marker randPriceMarker(String key, boolean training, long sync) throws RemoteException;
}