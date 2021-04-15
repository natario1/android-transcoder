package com.otaliastudios.transcoder.internal.utils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ISO6709LocationParserTest {

    @Test
    public void testParse() {
        ISO6709LocationParser parser = new ISO6709LocationParser();
        assertArrayEquals(new float[]{35.658632f, 139.745411f}, parser.parse("+35.658632+139.745411/"), 0);
        assertArrayEquals(new float[]{40.75f, -074.00f}, parser.parse("+40.75-074.00/"), 0);
        // with Altitude
        assertArrayEquals(new float[]{-90f, +0f}, parser.parse("-90+000+2800/"), 0);
        assertArrayEquals(new float[]{27.5916f, 086.5640f}, parser.parse("+27.5916+086.5640+8850/"), 0);
        // ranged data
        assertArrayEquals(new float[]{35.331f, 134.224f}, parser.parse("+35.331+134.224/+35.336+134.228/"), 0);
        assertArrayEquals(new float[]{35.331f, 134.224f}, parser.parse("+35.331+134.224/+35.336+134.228/+35.333+134.229/+35.333+134.227/"), 0);
    }

    @Test
    public void testParseFailure() {
        ISO6709LocationParser parser = new ISO6709LocationParser();
        assertNull(parser.parse(null));
        assertNull(parser.parse(""));
        assertNull(parser.parse("35 deg 65' 86.32\" N, 139 deg 74' 54.11\" E"));
        assertNull(parser.parse("+35.658632"));
        assertNull(parser.parse("+35.658632-"));
        assertNull(parser.parse("40.75-074.00"));
        assertNull(parser.parse("+40.75-074.00.00"));
    }
}