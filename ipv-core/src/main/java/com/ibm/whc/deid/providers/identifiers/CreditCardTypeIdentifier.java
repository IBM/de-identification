/*
 * (C) Copyright IBM Corp. 2016,2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.whc.deid.providers.identifiers;

import java.util.Arrays;
import java.util.Collection;

import com.ibm.whc.deid.models.ValueClass;
import com.ibm.whc.deid.providers.ProviderType;
import com.ibm.whc.deid.shared.localization.Resource;
import com.ibm.whc.deid.util.CreditCardTypeManager;
import com.ibm.whc.deid.util.Manager;
import com.ibm.whc.deid.util.ManagerFactory;

public class CreditCardTypeIdentifier extends AbstractManagerBasedIdentifier {
  /** */
  private static final long serialVersionUID = 8292073040447713030L;

  private static final String[] appropriateNames = {"Credit Card Type"};
	private CreditCardTypeManager creditCardTypeManager;

	protected volatile boolean initialized = false;

	public CreditCardTypeIdentifier(String tenantId, String localizationProperty) {
		super(tenantId, localizationProperty);
	}

  @Override
  protected Manager getManager() {
		if (!initialized) {
			creditCardTypeManager = (CreditCardTypeManager) ManagerFactory.getInstance().getManager(tenantId,
					Resource.CREDIT_CARD_TYPE, null, localizationProperty);

			initialized = true;
		}
		return creditCardTypeManager;
  }

  @Override
  protected Collection<String> getAppropriateNames() {
    return Arrays.asList(appropriateNames);
  }

  @Override
  public ProviderType getType() {
    return ProviderType.CREDIT_CARD_TYPE;
  }

  @Override
  public String getDescription() {
    return "Credit Card Type identification";
  }

  @Override
  public Collection<ProviderType> getLinkedTypes() {
    return Arrays.asList(new ProviderType[] {ProviderType.CREDIT_CARD});
  }

  @Override
  public ValueClass getValueClass() {
    return ValueClass.TEXT;
  }
}
