/*
 * (C) Copyright IBM Corp. 2016,2021
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.whc.deid.util;

import java.security.SecureRandom;
import java.util.List;
import com.ibm.whc.deid.resources.KeyListResource;
import com.ibm.whc.deid.shared.localization.Resource;

public class MSISDNManager {

  protected final SecureRandom random = new SecureRandom();

  protected final PhoneCountryCodesManager phoneCountryCodeManager;
  protected final PhoneAreaCodesManager phoneAreaCodesManager;
  protected final PhoneNumberLengthManager phoneNumberLengthManager;

  protected MSISDNManager(PhoneCountryCodesManager phoneCountryCodeManager,
      PhoneAreaCodesManager phoneAreaCodesManager,
      PhoneNumberLengthManager phoneNumberLengthManager) {
    this.phoneCountryCodeManager = phoneCountryCodeManager;
    this.phoneAreaCodesManager = phoneAreaCodesManager;
    this.phoneNumberLengthManager = phoneNumberLengthManager;
  }

  /**
   * Instantiates a new Msisdn manager.
   *
   * @param localizationProperty location of the localization property file
   */
  public static MSISDNManager buildMSISDNManager(String tenantId, String localizationProperty) {
    MSISDNManager mgr = new MSISDNManager(
        (PhoneCountryCodesManager) ManagerFactory.getInstance().getManager(tenantId,
            Resource.PHONE_CALLING_CODES, null, localizationProperty),
        (PhoneAreaCodesManager) ManagerFactory.getInstance().getManager(tenantId,
            Resource.PHONE_AREA_CODES, null, localizationProperty),
        (PhoneNumberLengthManager) ManagerFactory.getInstance().getManager(tenantId,
            Resource.PHONE_NUM_DIGITS, null, localizationProperty));
    return mgr;
  }

  public boolean isValidCountryNumDigits(String countryCode, int inputNumDigits) {
    List<Integer> numDigitsList = null;
    KeyListResource<Integer> resource = phoneNumberLengthManager.getValue(countryCode);
    if (resource != null) {
      numDigitsList = resource.getValue();
    }

    if (numDigitsList == null) {
      // since we do not know the number of phone digits for this country
      // code, assume the digits is valid.
      return true;
    }
    for (Integer numDigits : numDigitsList) {
      if (inputNumDigits == numDigits.intValue()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Is valid us number boolean.
   *
   * @param data the data
   * @return the boolean
   */
  public boolean isValidUSNumber(String data) {
    if (data.length() == 10) {
      for (int i = 0; i < data.length(); i++) {
        char c = data.charAt(i);
        if (c < '0' || c > '9') {
          return false;
        }
      }
      String areaCode = data.substring(0, 3);
      return phoneAreaCodesManager.isValidKey("USA", areaCode);
    }
    return false;
  }

  /**
   * Return a valid phone number length for a phone number accessed via the given phone country
   * code. If more than one length is valid for the given country code, one of the valid lengths is
   * randomly selected.
   * 
   * @param countryCode a phone country calling code, such as "1" for USA
   * 
   * @return a valid length for a phone number associated with the given country code or <i>null</i>
   *         if information is not known for the given country code
   */
  public Integer getRandomPhoneNumberDigitsByCountry(String countryCode) {
    Integer selectedLength = null;
    List<Integer> numDigitsList = null;
    KeyListResource<Integer> resource = phoneNumberLengthManager.getValue(countryCode);
    if (resource != null) {
      numDigitsList = resource.getValue();
    }
    if (numDigitsList != null) {
      int count = numDigitsList.size();
      if (count == 1) {
        selectedLength = numDigitsList.get(0);
      } else if (count > 1) {
        selectedLength = numDigitsList.get(random.nextInt(count));
      }
    }
    return selectedLength;
  }

  /**
   * Gets random country code.
   *
   * @return the random country code
   */
  public String getRandomCountryCode() {
    return phoneCountryCodeManager.getRandomKey();
  }

  /**
   * Is valid country code boolean.
   *
   * @param country the country
   * @return the boolean
   */
  public boolean isValidCountryCode(String country) {
    return phoneCountryCodeManager.isValidKey(country);
  }
}
