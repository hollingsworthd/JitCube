package com.machinepublishers.neuraltrader;

import com.machinepublishers.neuraltrader.Prices.Marker;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.Map;

public class ServerImpl implements Server {

  private final Prices prices;
  private final Map<Long, Marker> markers = new HashMap<>();
  private Marker marker1 = null;
  private Marker marker2 = null;

  public ServerImpl(Prices prices) {
    this.prices = prices;
  }

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

  @Override
  public Marker randPriceMarker(String key, boolean training, long sync) throws RemoteException {
    if (Main.getKey().equals(key)) {
      synchronized (ServerImpl.class) {
        if (markers.containsKey(sync)) {
          return markers.get(sync);
        }
        Marker toRemove = marker2;
        marker2 = marker1;
        marker1 = prices.rand(training);
        if (toRemove != null) {
          markers.remove(toRemove);
        }
        markers.put(sync, marker1);
        return marker1;
      }
    }
    return null;
  }
}