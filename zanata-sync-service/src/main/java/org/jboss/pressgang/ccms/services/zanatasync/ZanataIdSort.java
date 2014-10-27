/*
 * Copyright 2011-2014 Red Hat, Inc.
 *
 * This file is part of PressGang CCMS.
 *
 * PressGang CCMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PressGang CCMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with PressGang CCMS. If not, see <http://www.gnu.org/licenses/>.
 */

package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.Comparator;

public class ZanataIdSort implements Comparator<String> {
    @Override
    public int compare(final String o1, final String o2) {
        if (o1 == null && o2 == null) return 0;
        if (o1 == null) return -1;
        if (o2 == null) return 1;

        final String[] vals1 = o1.split("-");
        final String[] vals2 = o2.split("-");

        // Convert the id string to an integer
        Integer id1 = null;
        try {
            id1 = Integer.parseInt(vals1[0]);
        } catch (NumberFormatException e) {

        }
        Integer id2 = null;
        try {
            id2 = Integer.parseInt(vals2[0]);
        } catch (NumberFormatException e) {

        }

        // Handle invalid ids/null values
        if (id1 == null && id2 == null) return 0;
        if (id1 == null) return 1;
        if (id2 == null) return -1;

        return id2.compareTo(id1);
    }
}
