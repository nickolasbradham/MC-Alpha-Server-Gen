package nbradham.alphaGen;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Scanner;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;

public final class Manager {
	private static final String S_SERVER_FILE = "server.jar", S_WORLD = "world", S_LEVEL_DAT = "level.dat",
			S_SEED = "RandomSeed";
	private static final File D_WORK = new File("workdir"), F_SERVER = new File(D_WORK, S_SERVER_FILE),
			F_LEVEL_DAT = Paths.get(D_WORK.getPath(), S_WORLD, S_LEVEL_DAT).toFile();

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
				return Dir.RIGHT;
			case LEFT:
				return Dir.DOWN;
			case RIGHT:
				return Dir.UP;
			default:
				return Dir.LEFT;
			}
		}
	};

	private final int radius;

	private Dir dir = Dir.DOWN;
	private int x = -1, y = 0;
	private byte lineN = 0, lineMax = 0;
	private boolean incMax = true;

	public Manager(short chunkRadius) {
		radius = chunkRadius / 20;
	}

	private void start() throws MalformedURLException, IOException, URISyntaxException, InterruptedException {
		D_WORK.mkdir();
		if (!F_SERVER.exists()) {
			FileOutputStream serverFos = new FileOutputStream(F_SERVER);
			serverFos.getChannel()
					.transferFrom(Channels.newChannel(
							new URI("https://files.betacraft.uk/server-archive/alpha/a0.1.0.jar").toURL().openStream()),
							0, Long.MAX_VALUE);
			serverFos.close();
		}
		if (!F_LEVEL_DAT.exists())
			generate(S_SERVER_FILE, D_WORK);
		CompoundTag data = ((CompoundTag) NBTUtil.read(F_LEVEL_DAT).getTag());
		long seed = data.getCompoundTag("Data").getLong(S_SEED);
		Thread[] workers = new Thread[Runtime.getRuntime().availableProcessors()];
		for (byte i = 0; i < workers.length; ++i)
			(workers[i] = new Thread(new Worker(i, seed))).start();
		for (Thread t : workers)
			t.join();
		System.out.println("Done.");
	}

	private synchronized int[] nextPos() {
		if (++lineN > lineMax) {
			dir = dir.next();
			lineN = 1;
			if (incMax = !incMax)
				++lineMax;
		}
		return Math.abs(x += dir.dx) > radius || Math.abs(y += dir.dy) > radius ? null : new int[] { x, y };
	}

	private void generate(String srvPath, File workDir) throws IOException, InterruptedException {
		boolean notDone = true;
		do {
			Process p = new ProcessBuilder(new String[] { "java", "-jar", srvPath, "nogui" }).directory(workDir)
					.start();
			OutputStream os = p.getOutputStream();
			os.write("stop".getBytes());
			os.close();
			Scanner scan = new Scanner(p.getErrorStream());
			while (scan.hasNextLine() && notDone)
				notDone = !scan.nextLine().endsWith(" [INFO] Done! For help, type \"help\" or \"?\"");
			scan.close();
			p.waitFor();
		} while (notDone);
	}

	public static void main(String[] args)
			throws MalformedURLException, IOException, URISyntaxException, InterruptedException {
		try {
			new Manager(Short.parseShort(args[0])).start();
		} catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
			System.err.println("Arguments: <chunkRadius>");
		}
	}

	private final class Worker implements Runnable {
		private static final File D_WORKERS = new File(D_WORK, "workers"), D_DEST = new File(D_WORK, S_WORLD);
		private static final short I_SPAWN_GEN = 320;

		private final File workDir, props, levelDir, levelDat;
		private final byte id;
		private final CompoundTag root = new CompoundTag(), data = new CompoundTag();
		private final NamedTag nbt = new NamedTag("", root);

		public Worker(byte workerID, long seed) {
			props = new File(workDir = new File(D_WORKERS, String.format("worker%d", id = workerID)),
					"server.properties");
			levelDat = new File(levelDir = new File(workDir.getPath(), S_WORLD), S_LEVEL_DAT);
			data.putLong(S_SEED, seed);
			root.put("Data", data);
		}

		@Override
		public void run() {
			workDir.mkdirs();
			if (!props.exists()) {
				try {
					FileOutputStream propsFos = new FileOutputStream(props);
					propsFos.write(String.format("server-port=%d", 25565 + id).getBytes());
					propsFos.close();
				} catch (IOException e) {
					printf("IOException occurred: %s", e);
					return;
				}
			}
			levelDir.mkdir();
			int[] pos;
			while ((pos = nextPos()) != null) {
				printf("Generating area (%d, %d)...", id, pos[0], pos[1]);
				data.putInt("SpawnX", pos[0] * I_SPAWN_GEN);
				data.putInt("SpawnZ", pos[1] * I_SPAWN_GEN);
				try {
					NBTUtil.write(nbt, levelDat);
					generate(F_SERVER.getAbsolutePath(), workDir);
					Queue<File[]> q = new LinkedList<>();
					q.offer(new File[] { levelDir, D_DEST });
					File[] op;
					while (!q.isEmpty()) {
						if ((op = q.poll())[0].isDirectory()) {
							op[1].mkdirs();
							for (File f : op[0].listFiles())
								q.offer(new File[] { f, new File(op[1], f.getName()) });
						} else if (!op[1].exists()) {
							boolean move = true;
							while (move)
								try {
									Files.move(op[0].toPath(), op[1].toPath(), StandardCopyOption.REPLACE_EXISTING);
									move = false;
								} catch (FileAlreadyExistsException e) {
									printf("Exception occurred: %s%n\tWill retry.", e);
								}
						}
					}
					Queue<File> delQ = new LinkedList<>();
					for (File f : levelDir.listFiles())
						delQ.offer(f);
					File fi;
					while (!delQ.isEmpty())
						if ((fi = delQ.poll()).isDirectory() && fi.list().length != 0) {
							for (File f : fi.listFiles())
								delQ.offer(f);
							delQ.offer(fi);
						} else
							fi.delete();
				} catch (InterruptedException | IOException e) {
					printf("Exception occurred: %s", e);
				}
			}
			printf("Done.");
		}

		private void printf(String format, Object... args) {
			System.out.printf("Thread %d: %s%n", id, String.format(format, args));
		}
	}
}