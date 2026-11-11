/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

/**
 * Interface for keybox providers.
 *
 * This interface defines the methods that a keybox provider must implement
 * to provide access to EC and RSA keys and certificate chains.
 *
 * @hide
 */
public interface IKeyboxProvider {

    /**
     * Checks if a valid keybox is available.
     *
     * @return true if a valid keybox is available, false otherwise
     * @hide
     */
    boolean hasKeybox();

    /**
     * Checks if a valid EC keybox is available.
     *
     * @return true if EC private key and cert chain are available
     * @hide
     */
    default boolean hasEcKeybox() {
        String key = getEcPrivateKey();
        String[] chain = getEcCertificateChain();
        return key != null && !key.isEmpty() && chain != null && chain.length > 0;
    }

    /**
     * Checks if a valid RSA keybox is available.
     *
     * @return true if RSA private key and cert chain are available
     * @hide
     */
    default boolean hasRsaKeybox() {
        String key = getRsaPrivateKey();
        String[] chain = getRsaCertificateChain();
        return key != null && !key.isEmpty() && chain != null && chain.length > 0;
    }

    /**
     * Retrieves the EC private key.
     *
     * @return the EC private key as a String
     * @hide
     */
    String getEcPrivateKey();

    /**
     * Retrieves the RSA private key.
     *
     * @return the RSA private key as a String
     * @hide
     */
    String getRsaPrivateKey();

    /**
     * Retrieves the EC certificate chain.
     *
     * @return an array of Strings representing the EC certificate chain
     * @hide
     */
    String[] getEcCertificateChain();

    /**
     * Retrieves the RSA certificate chain.
     *
     * @return an array of Strings representing the RSA certificate chain
     * @hide
     */
    String[] getRsaCertificateChain();
}
