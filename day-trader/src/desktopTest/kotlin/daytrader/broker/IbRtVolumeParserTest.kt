package daytrader.broker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IbRtVolumeParserTest {
    @Test
    fun tradeSizeFromRtVolume_parsesLastSizeField() {
        assertEquals(500.0, IbRtVolumeParser.tradeSizeFromRtVolume("150.25;500;123456;10000;150.20;true"))
    }

    @Test
    fun tradeSizeFromRtVolume_rejectsZeroOrMissing() {
        assertNull(IbRtVolumeParser.tradeSizeFromRtVolume("150.25;0;123456;10000;150.20;true"))
        assertNull(IbRtVolumeParser.tradeSizeFromRtVolume("150.25"))
    }
}
