/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package com.atvpm.tvcontrol.shell;
public interface IPowerShell extends android.os.IInterface
{
  /** Default implementation for IPowerShell. */
  public static class Default implements com.atvpm.tvcontrol.shell.IPowerShell
  {
    /**
     * Executes a single command once via /system/bin/sh -c.
     * 
     * @return Bundle with keys "out" (String), "err" (String) and "code" (int).
     */
    @Override public android.os.Bundle exec(java.lang.String command) throws android.os.RemoteException
    {
      return null;
    }
    /** Linux uid the service runs as: 0 for root, 2000 for ADB shell. */
    @Override public int uid() throws android.os.RemoteException
    {
      return 0;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.atvpm.tvcontrol.shell.IPowerShell
  {
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.atvpm.tvcontrol.shell.IPowerShell interface,
     * generating a proxy if needed.
     */
    public static com.atvpm.tvcontrol.shell.IPowerShell asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.atvpm.tvcontrol.shell.IPowerShell))) {
        return ((com.atvpm.tvcontrol.shell.IPowerShell)iin);
      }
      return new com.atvpm.tvcontrol.shell.IPowerShell.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
      }
      switch (code)
      {
        case TRANSACTION_exec:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.os.Bundle _result = this.exec(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_uid:
        {
          int _result = this.uid();
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.atvpm.tvcontrol.shell.IPowerShell
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      /**
       * Executes a single command once via /system/bin/sh -c.
       * 
       * @return Bundle with keys "out" (String), "err" (String) and "code" (int).
       */
      @Override public android.os.Bundle exec(java.lang.String command) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.Bundle _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(command);
          boolean _status = mRemote.transact(Stub.TRANSACTION_exec, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, android.os.Bundle.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Linux uid the service runs as: 0 for root, 2000 for ADB shell. */
      @Override public int uid() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_uid, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_exec = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_uid = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  public static final java.lang.String DESCRIPTOR = "com.atvpm.tvcontrol.shell.IPowerShell";
  /**
   * Executes a single command once via /system/bin/sh -c.
   * 
   * @return Bundle with keys "out" (String), "err" (String) and "code" (int).
   */
  public android.os.Bundle exec(java.lang.String command) throws android.os.RemoteException;
  /** Linux uid the service runs as: 0 for root, 2000 for ADB shell. */
  public int uid() throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
