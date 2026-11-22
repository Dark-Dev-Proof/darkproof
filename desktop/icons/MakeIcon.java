import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws DarkProof's desktop icon from the phones' logo: the white mark on a black tile, as on the phones' home screens.
 * Writes DarkProof.ico (for the Windows installer, the program and its shortcuts), ../src/main/resources/icon.png
 * (for the window and the taskbar) and the pictures for a Mac's icon in DarkProof.iconset. Run it from this folder
 * after the logo changes: java MakeIcon.java, then on a Mac: iconutil -c icns DarkProof.iconset && rm -r DarkProof.iconset
 */
public class MakeIcon {
    public static void main(String[] a) throws Exception {
        BufferedImage logo = ImageIO.read(new File("../../app/src/main/res/drawable-nodpi/logo.png"));
        ImageIO.write(tile(logo, 512), "png", new File("../src/main/resources/icon.png"));
        int[] sizes = {16, 20, 24, 32, 40, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int n : sizes) images.add(n == 256 ? png(tile(logo, n)) : dib(tile(logo, n)));
        // A Mac's icon: the same tile, a little smaller on a clear ground, as Mac icons are drawn, at the sizes a Mac asks for
        File set = new File("DarkProof.iconset"); set.mkdirs();
        for (int n : new int[] {16, 32, 128, 256, 512}) {
            ImageIO.write(onClearGround(logo, n), "png", new File(set, "icon_" + n + "x" + n + ".png"));
            ImageIO.write(onClearGround(logo, 2 * n), "png", new File(set, "icon_" + n + "x" + n + "@2x.png"));
        }
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream("DarkProof.ico"))) {
            ByteBuffer head = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
            head.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
            int at = head.capacity();
            for (int i = 0; i < sizes.length; i++) {
                head.put((byte) (sizes[i] == 256 ? 0 : sizes[i])).put((byte) (sizes[i] == 256 ? 0 : sizes[i])).put((byte) 0).put((byte) 0)
                    .putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(at);
                at += images.get(i).length;
            }
            out.write(head.array());
            for (byte[] image : images) out.write(image);
        }
    }

    /** The mark, white, in the middle of a black tile with round corners and a faint edge so it shows on a dark taskbar. */
    static BufferedImage tile(BufferedImage logo, int n) {
        int big = Math.max(n, 256) * 4; // drawn large, then averaged down: small sizes stay clean
        BufferedImage img = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double r = big * 0.23, edge = big * 0.012;
        g.setColor(new Color(0x2E2E2E)); g.fill(new RoundRectangle2D.Double(0, 0, big, big, 2 * r, 2 * r));
        g.setColor(Color.BLACK); g.fill(new RoundRectangle2D.Double(edge, edge, big - 2 * edge, big - 2 * edge, 2 * (r - edge), 2 * (r - edge)));
        int mark = (int) (big * (n <= 24 ? 0.70 : 0.60)), off = (big - mark) / 2; // the smallest sizes need a larger mark to read
        g.drawImage(white(logo), off, off, mark, mark, null);
        g.dispose();
        while (img.getWidth() > n) { // halve until the size is reached, the last step to the exact size
            int next = Math.max(n, img.getWidth() / 2);
            BufferedImage s = new BufferedImage(next, next, BufferedImage.TYPE_INT_ARGB);
            Graphics2D h = s.createGraphics();
            h.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            h.drawImage(img, 0, 0, next, next, null); h.dispose(); img = s;
        }
        return img;
    }

    /** The tile at four fifths of the picture, with clear room around it. */
    static BufferedImage onClearGround(BufferedImage logo, int n) {
        int inner = Math.round(n * 0.805f), off = (n - inner) / 2;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(tile(logo, inner), off, off, null); g.dispose();
        return img;
    }

    /** The logo with every pixel white and its own transparency: the file may be drawn in any colour. */
    static BufferedImage white(BufferedImage logo) {
        BufferedImage w = new BufferedImage(logo.getWidth(), logo.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < logo.getHeight(); y++) for (int x = 0; x < logo.getWidth(); x++) w.setRGB(x, y, (logo.getRGB(x, y) & 0xFF000000) | 0xFFFFFF);
        return w;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); ImageIO.write(img, "png", b); return b.toByteArray();
    }

    /** An icon's picture the old way, which every Windows reads at small sizes: 32-bit pixels bottom row first, then an empty mask. */
    static byte[] dib(BufferedImage img) {
        int n = img.getWidth(), maskRow = ((n + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + n * n * 4 + maskRow * n).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(n).putInt(2 * n).putShort((short) 1).putShort((short) 32).putInt(0).putInt(n * n * 4 + maskRow * n).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = n - 1; y >= 0; y--) for (int x = 0; x < n; x++) b.putInt(img.getRGB(x, y)); // ARGB written little-endian is B, G, R, A
        return b.array();
    }
}
