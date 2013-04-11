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
