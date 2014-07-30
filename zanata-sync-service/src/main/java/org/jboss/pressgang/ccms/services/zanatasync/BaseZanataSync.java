/*
  Copyright 2011-2014 Red Hat, Inc

  This file is part of PressGang CCMS.

  PressGang CCMS is free software: you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  PressGang CCMS is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License
  along with PressGang CCMS.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.zanata.common.LocaleId;

public abstract class BaseZanataSync {
    private final DataProviderFactory providerFactory;
    private final ZanataInterface zanataInterface;
    private final AtomicInteger syncProgress = new AtomicInteger(0);

    protected BaseZanataSync(final DataProviderFactory providerFactory, final ZanataInterface zanataInterface) {
        this.providerFactory = providerFactory;
        this.zanataInterface = zanataInterface;
    }

    protected DataProviderFactory getProviderFactory() {
        return providerFactory;
    }

    protected ZanataInterface getZanataInterface() {
        return zanataInterface;
    }

    public abstract void processZanataResources(final Set<String> zanataIds, final List<LocaleId> locales);

    public Integer getProgress() {
        return syncProgress.get();
    }

    protected void setProgress(final Integer progress) {
        syncProgress.set(progress);
    }

    protected void setProgress(final Long progress) {
        syncProgress.set(progress.intValue());
    }

    protected void addProgress(final Integer amount) {
        syncProgress.addAndGet(amount);
    }
}
