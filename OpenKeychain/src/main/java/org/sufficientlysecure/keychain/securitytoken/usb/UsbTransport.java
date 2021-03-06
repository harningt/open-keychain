/*
 * Copyright (C) 2016 Nikita Mikhailov <nikita.s.mikhailov@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.securitytoken.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.securitytoken.Transport;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import org.sufficientlysecure.keychain.securitytoken.usb.tpdu.T1TpduProtocol;
import org.sufficientlysecure.keychain.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Based on USB CCID Specification rev. 1.1
 * http://www.usb.org/developers/docs/devclass_docs/DWG_Smart-Card_CCID_Rev110.pdf
 * Implements small subset of these features
 */
public class UsbTransport implements Transport {
    private static final int PROTOCOLS_OFFSET = 6;
    private static final int FEATURES_OFFSET = 40;
    private static final short MASK_T1_PROTO = 2;

    // dwFeatures Masks
    private static final int MASK_TPDU = 0x10000;
    private static final int MASK_SHORT_APDU = 0x20000;
    private static final int MASK_EXTENDED_APDU = 0x40000;


    private final UsbManager mUsbManager;
    private final UsbDevice mUsbDevice;
    private UsbInterface mUsbInterface;
    private UsbEndpoint mBulkIn;
    private UsbEndpoint mBulkOut;
    private UsbDeviceConnection mConnection;
    private CcidTransceiver mTransceiver;
    private CcidTransportProtocol mProtocol;

    public UsbTransport(UsbDevice usbDevice, UsbManager usbManager) {
        mUsbDevice = usbDevice;
        mUsbManager = usbManager;
    }

    /**
     * Get first class 11 (Chip/Smartcard) interface of the device
     *
     * @param device {@link UsbDevice} which will be searched
     * @return {@link UsbInterface} of smartcard or null if it doesn't exist
     */
    @Nullable
    private static UsbInterface getSmartCardInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface anInterface = device.getInterface(i);
            if (anInterface.getInterfaceClass() == UsbConstants.USB_CLASS_CSCID) {
                return anInterface;
            }
        }
        return null;
    }

    /**
     * Get device's bulk-in and bulk-out endpoints
     *
     * @param usbInterface usb device interface
     * @return pair of builk-in and bulk-out endpoints respectively
     */
    @NonNull
    private static Pair<UsbEndpoint, UsbEndpoint> getIoEndpoints(final UsbInterface usbInterface) {
        UsbEndpoint bulkIn = null, bulkOut = null;
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            final UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }

            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                bulkIn = endpoint;
            } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                bulkOut = endpoint;
            }
        }
        return new Pair<>(bulkIn, bulkOut);
    }

    /**
     * Release interface and disconnect
     */
    @Override
    public void release() {
        if (mConnection != null) {
            mConnection.releaseInterface(mUsbInterface);
            mConnection.close();
            mConnection = null;
        }

        Log.d(Constants.TAG, "Usb transport disconnected");
    }

    /**
     * Check if device is was connected to and still is connected
     * @return true if device is connected
     */
    @Override
    public boolean isConnected() {
        return mConnection != null && mUsbManager.getDeviceList().containsValue(mUsbDevice) &&
                mConnection.getSerial() != null;
    }

    /**
     * Check if Transport supports persistent connections e.g connections which can
     * handle multiple operations in one session
     * @return true if transport supports persistent connections
     */
    @Override
    public boolean isPersistentConnectionAllowed() {
        return true;
    }

    /**
     * Connect to OTG device
     * @throws IOException
     */
    @Override
    public void connect() throws IOException {
        mUsbInterface = getSmartCardInterface(mUsbDevice);
        if (mUsbInterface == null) {
            // Shouldn't happen as we whitelist only class 11 devices
            throw new UsbTransportException("USB error - device doesn't have class 11 interface");
        }

        final Pair<UsbEndpoint, UsbEndpoint> ioEndpoints = getIoEndpoints(mUsbInterface);
        mBulkIn = ioEndpoints.first;
        mBulkOut = ioEndpoints.second;

        if (mBulkIn == null || mBulkOut == null) {
            throw new UsbTransportException("USB error - invalid class 11 interface");
        }

        mConnection = mUsbManager.openDevice(mUsbDevice);
        if (mConnection == null) {
            throw new UsbTransportException("USB error - failed to connect to device");
        }

        if (!mConnection.claimInterface(mUsbInterface, true)) {
            throw new UsbTransportException("USB error - failed to claim interface");
        }

        mTransceiver = new CcidTransceiver(mConnection, mBulkIn, mBulkOut);



        configureProtocol();
    }

    private void configureProtocol() throws UsbTransportException {
        byte[] desc = mConnection.getRawDescriptors();
        int dwProtocols = 0, dwFeatures = 0;
        boolean hasCcidDescriptor = false;

        ByteBuffer byteBuffer = ByteBuffer.wrap(desc).order(ByteOrder.LITTLE_ENDIAN);

        while (byteBuffer.hasRemaining()) {
            byteBuffer.mark();
            byte len = byteBuffer.get(), type = byteBuffer.get();

            if (type == 0x21 && len == 0x36) {
                byteBuffer.reset();

                byteBuffer.position(byteBuffer.position() + PROTOCOLS_OFFSET);
                dwProtocols = byteBuffer.getInt();

                byteBuffer.reset();

                byteBuffer.position(byteBuffer.position() + FEATURES_OFFSET);
                dwFeatures = byteBuffer.getInt();
                hasCcidDescriptor = true;
                break;
            } else {
                byteBuffer.position(byteBuffer.position() + len - 2);
            }
        }

        if (!hasCcidDescriptor) {
            throw new UsbTransportException("CCID descriptor not found");
        }

        if ((dwProtocols & MASK_T1_PROTO) == 0) {
            throw new UsbTransportException("T=0 protocol is not supported");
        }

        if ((dwFeatures & MASK_TPDU) != 0) {
            mProtocol = new T1TpduProtocol(mTransceiver);
        } else if (((dwFeatures & MASK_SHORT_APDU) != 0) || ((dwFeatures & MASK_EXTENDED_APDU) != 0)) {
            mProtocol = new T1ShortApduProtocol(mTransceiver);
        } else {
            throw new UsbTransportException("Character level exchange is not supported");
        }
    }

    /**
     * Transmit and receive data
     * @param data data to transmit
     * @return received data
     * @throws UsbTransportException
     */
    @Override
    public ResponseAPDU transceive(CommandAPDU data) throws UsbTransportException {
        return new ResponseAPDU(mProtocol.transceive(data.getBytes()));
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        final UsbTransport that = (UsbTransport) o;

        return mUsbDevice != null ? mUsbDevice.equals(that.mUsbDevice) : that.mUsbDevice == null;
    }

    @Override
    public int hashCode() {
        return mUsbDevice != null ? mUsbDevice.hashCode() : 0;
    }

    public UsbDevice getUsbDevice() {
        return mUsbDevice;
    }
}
