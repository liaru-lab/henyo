package link.liaru.henyo;

public final class ScreenshotCoordinateMapperTest {
    private static void expect(int x, int y, int imageWidth, int imageHeight,
                               int left, int top, int right, int bottom,
                               int expectedX, int expectedY) {
        ScreenshotCoordinateMapper.Result result = ScreenshotCoordinateMapper.map(
                x, y, imageWidth, imageHeight, left, top, right, bottom);
        if (!result.ok || result.x != expectedX || result.y != expectedY) {
            throw new AssertionError("unexpected mapping: " + result.ok + " " + result.x + "," + result.y);
        }
    }

    public static void main(String[] args) {
        expect(540, 300, 1080, 2299, 0, 113, 1080, 2412, 540, 413);
        expect(250, 250, 500, 500, 100, 200, 1100, 1200, 600, 700);
        expect(1079, 2298, 1080, 2299, 0, 113, 1080, 2412, 1079, 2411);
        ScreenshotCoordinateMapper.Result outside = ScreenshotCoordinateMapper.map(
                1080, 0, 1080, 2299, 0, 113, 1080, 2412);
        if (outside.ok || !"screenshot_coordinate_out_of_bounds".equals(outside.error)) {
            throw new AssertionError("out-of-bounds point was accepted");
        }
        ScreenshotCoordinateMapper.Result invalid = ScreenshotCoordinateMapper.map(
                0, 0, 0, 2299, 0, 113, 1080, 2412);
        if (invalid.ok || !"capture_mapping_uncertain".equals(invalid.error)) {
            throw new AssertionError("invalid mapping was accepted");
        }
        System.out.println("screenshot coordinate mapper verifier passed");
    }
}
