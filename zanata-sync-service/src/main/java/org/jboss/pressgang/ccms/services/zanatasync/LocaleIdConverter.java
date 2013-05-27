package org.jboss.pressgang.ccms.services.zanatasync;

import com.beust.jcommander.IStringConverter;
import org.zanata.common.LocaleId;

public class LocaleIdConverter  implements IStringConverter<LocaleId> {
    @Override
    public LocaleId convert(String value) {
        return LocaleId.fromJavaName(value);
    }
}
