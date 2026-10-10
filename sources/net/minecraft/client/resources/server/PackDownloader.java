package net.minecraft.client.resources.server;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.packs.DownloadQueue.BatchResult;
import net.minecraft.server.packs.DownloadQueue.DownloadRequest;

public interface PackDownloader {
	void download(Map<UUID, DownloadRequest> requests, Consumer<BatchResult> output);
}
