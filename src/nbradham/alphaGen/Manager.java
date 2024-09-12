package nbradham.alphaGen;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.Scanner;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.IntTag;

public final class Manager {

	private static enum Dir {
		UP(0, 1), RIGHT(1, 0), DOWN(0, -1), LEFT(-1, 0);

		private final int dx, dy;

		/**
		 * Constructs a new Dir with movement info.
		 * 
		 * @param x The x movement.
		 * @param y The y movement.
		 */
		private Dir(int x, int y) {
			dx = x;
			dy = y;
		}

		/**
		 * Retrieves the next direction in the spiral.
		 * 
		 * @param cur The current direction.
		 * @return The next direction to travel.
		 */
		private Dir next() {
			switch (this) {
			case DOWN:
				return Dir.LEFT;
			case LEFT:
				return Dir.UP;
			case RIGHT:
				return Dir.DOWN;
			case UP:
				return Dir.RIGHT;
			}
			return null;
		}
	};

	boolean run = true;

	private void start() throws IOException {
		Scanner scan = new Scanner(System.in);
		new Thread(new Worker()).start();
		while (!scan.next().equals("q"))
			System.out.println("Enter \"q\" to quit.");
		run = false;
		scan.close();
	}

	public static void main(String[] args) throws IOException {
		new Manager().start();
	}

	private final class Worker implements Runnable {
		private static final String DAT = "C:\\Users\\bradh\\Downloads\\world\\level.dat";
		private static final short DIST = 512;
		private final NamedTag nbt;
		private final CompoundTag data;
		private byte x = 0, y = 0;

		private Worker() throws IOException {
			super();
			nbt = NBTUtil.read(DAT);
			data = ((CompoundTag) nbt.getTag()).getCompoundTag("Data");
		}

		private void gen() throws IOException, InterruptedException {
			data.put("SpawnX", new IntTag(x * DIST));
			data.put("SpawnZ", new IntTag(y * DIST));
			System.out.printf("Generating: (%d, %d)%n", ((IntTag) data.get("SpawnX")).asInt(),
					((IntTag) data.get("SpawnZ")).asInt());
			NBTUtil.write(nbt, DAT);
			boolean notDone = true;
			while (notDone) {
				Process p = new ProcessBuilder(new String[] { "java", "-jar", "a0.1.0.jar", "nogui" })
						.directory(new File("C:\\Users\\bradh\\Downloads")).start();
				OutputStream os = p.getOutputStream();
				os.write("stop".getBytes());
				os.close();
				Scanner scan = new Scanner(p.getErrorStream());
				while (scan.hasNext())
					if (scan.nextLine().contains(" [INFO] Done! For help, type \"help\" or \"?\""))
						notDone = false;
				scan.close();
				p.waitFor();
				if (notDone)
					System.out.println("Retrying...");
			}
		}

		@Override
		public void run() {
			byte t = 0;
			try {
				gen();
				Dir d = Dir.LEFT;
				while (run) {
					++t;
					for (byte n = 0; n < 2 && run; ++n) {
						d = d.next();
						for (byte i = 0; i < t && run; ++i) {
							x += d.dx;
							y += d.dy;
							gen();
						}
					}
				}
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}