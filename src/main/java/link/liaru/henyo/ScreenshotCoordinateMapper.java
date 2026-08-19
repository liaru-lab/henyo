package link.liaru.henyo;

final class ScreenshotCoordinateMapper {
    private ScreenshotCoordinateMapper() {}

    static Result map(int x, int y, int imageWidth, int imageHeight,
                      int left, int top, int right, int bottom) {
        if (imageWidth <= 0 || imageHeight <= 0 || right <= left || bottom <= top) {
            return Result.error("capture_mapping_uncertain");
        }
        if (x < 0 || y < 0 || x >= imageWidth || y >= imageHeight) {
            return Result.error("screenshot_coordinate_out_of_bounds");
        }
        double scaleX = ((double) (right - left)) / imageWidth;
        double scaleY = ((double) (bottom - top)) / imageHeight;
        int screenX = left + (int) Math.round(x * scaleX);
        int screenY = top + (int) Math.round(y * scaleY);
        screenX = Math.max(left, Math.min(right - 1, screenX));
        screenY = Math.max(top, Math.min(bottom - 1, screenY));
        return Result.ok(screenX, screenY);
    }

    static final class Result {
        final boolean ok;
        final int x;
        final int y;
        final String error;

        private Result(boolean ok, int x, int y, String error) {
            this.ok = ok;
            this.x = x;
            this.y = y;
            this.error = error;
        }

        static Result ok(int x, int y) {
            return new Result(true, x, y, "");
        }

        static Result error(String error) {
            return new Result(false, 0, 0, error);
        }
    }
}
