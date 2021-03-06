package com.vaguehope.dlnatoad.media;

import java.io.File;
import java.io.FilenameFilter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teleal.cling.support.model.DIDLObject;
import org.teleal.cling.support.model.PersonWithRole;
import org.teleal.cling.support.model.Res;
import org.teleal.cling.support.model.WriteStatus;
import org.teleal.cling.support.model.container.Container;
import org.teleal.cling.support.model.item.AudioItem;
import org.teleal.cling.support.model.item.ImageItem;
import org.teleal.cling.support.model.item.Item;
import org.teleal.cling.support.model.item.VideoItem;
import org.teleal.common.util.MimeType;

import com.vaguehope.dlnatoad.dlnaserver.ContentGroup;
import com.vaguehope.dlnatoad.dlnaserver.ContentNode;
import com.vaguehope.dlnatoad.dlnaserver.ContentTree;
import com.vaguehope.dlnatoad.media.MetadataReader.Metadata;
import com.vaguehope.dlnatoad.util.HashHelper;
import com.vaguehope.dlnatoad.util.Watcher.EventType;
import com.vaguehope.dlnatoad.util.Watcher.FileListener;

public class MediaIndex implements FileListener {

	public enum HierarchyMode {
		FLATTERN,
		PRESERVE
	}

	private static final Logger LOG = LoggerFactory.getLogger(MediaIndex.class);

	private final ContentTree contentTree;
	private final String externalHttpContext;
	private final HierarchyMode hierarchyMode;

	private final Container videoContainer;
	private final Container imageContainer;
	private final Container audioContainer;

	public MediaIndex (final ContentTree contentTree, final String externalHttpContext, final HierarchyMode hierarchyMode) {
		this.contentTree = contentTree;
		this.externalHttpContext = externalHttpContext;
		this.hierarchyMode = hierarchyMode;

		this.videoContainer = makeFormatContainerOnTree(contentTree.getRootNode(), ContentGroup.VIDEO);
		this.imageContainer = makeFormatContainerOnTree(contentTree.getRootNode(), ContentGroup.IMAGE);
		this.audioContainer = makeFormatContainerOnTree(contentTree.getRootNode(), ContentGroup.AUDIO);
	}

	@Override
	public void fileFound (final File rootDir, final File file, final EventType eventType) {
		if (!rootDir.exists()) throw new IllegalArgumentException("Not found: " + rootDir);
		if (!file.isFile()) return;
		if (!MediaFormat.MediaFileFilter.INSTANCE.accept(file)) return;
		putFileToContentTree(rootDir, file);
		if (eventType == EventType.NOTIFY) LOG.info("shared: {}", file.getAbsolutePath());
	}

	@Override
	public void fileGone (final File file) {
		this.contentTree.prune(); // FIXME be less lazy.
	}

	private void putFileToContentTree (final File rootDir, final File file) {
		final MediaFormat format = MediaFormat.identify(file);
		final File dir = file.getParentFile();
		final Container formatContainer;
		switch (format.getContentGroup()) { // FIXME make hashmap.
			case VIDEO:
				formatContainer = this.videoContainer;
				break;
			case IMAGE:
				formatContainer = this.imageContainer;
				break;
			case AUDIO:
				formatContainer = this.audioContainer;
				break;
			default:
				throw new IllegalStateException();
		}

		final Container dirContainer;
		switch (this.hierarchyMode) {
			case PRESERVE:
				dirContainer = makeDirAndParentDirsContianersOnTree(format, formatContainer, rootDir, dir);
				break;
			case FLATTERN:
			default:
				dirContainer = makeDirContainerOnTree(format.getContentGroup(), formatContainer, contentId(format.getContentGroup(), dir), dir);
		}

		makeItemInContainer(format, dirContainer, file, file.getName());
		synchronized (dirContainer) {
			Collections.sort(dirContainer.getItems(), DIDLObjectOrder.TITLE);
		}
	}

	private Container makeFormatContainerOnTree (final ContentNode parentNode, final ContentGroup group) {
		return makeContainerOnTree(parentNode, group.getId(), group.getHumanName());
	}

	private Container makeDirContainerOnTree (final ContentGroup contentGroup, final Container parentContainer, final String id, final File dir) {
		final ContentNode parentNode = this.contentTree.getNode(parentContainer.getId());
		final Container dirContainer = makeContainerOnTree(parentNode, id, dir.getName());
		dirContainer.setCreator(dir.getAbsolutePath());
		synchronized (parentContainer) {
			Collections.sort(parentNode.getContainer().getContainers(), DIDLObjectOrder.CREATOR);
		}

		final Res artRes = findArtRes(dir, contentGroup);
		if (artRes != null) {
			synchronized (dirContainer) {
				dirContainer.addProperty(new DIDLObject.Property.UPNP.ALBUM_ART_URI(URI.create(artRes.getValue())));
			}
		}

		return dirContainer;
	}

	private Container makeDirAndParentDirsContianersOnTree (final MediaFormat format, final Container formatContainer, final File rootDir, final File dir) {
		final List<File> dirsToCreate = new ArrayList<>();

		File ittrDir = dir;
		while (ittrDir != null) {
			final ContentNode node = this.contentTree.getNode(contentId(format.getContentGroup(), ittrDir));
			if (node != null) break;
			dirsToCreate.add(ittrDir);
			if (rootDir.equals(ittrDir)) break;
			ittrDir = ittrDir.getParentFile();
		}

		Collections.reverse(dirsToCreate);

		for (final File dirToCreate : dirsToCreate) {
			final Container parentContainer;
			if (rootDir.equals(dirToCreate)) {
				parentContainer = formatContainer;
			}
			else {
				final ContentNode parentNode = this.contentTree.getNode(contentId(format.getContentGroup(), dirToCreate.getParentFile()));
				parentContainer = parentNode.getContainer();
			}
			makeDirContainerOnTree(format.getContentGroup(), parentContainer, contentId(format.getContentGroup(), dirToCreate), dirToCreate);
		}

		return this.contentTree.getNode(contentId(format.getContentGroup(), dir)).getContainer();
	}

	/**
	 * If it already exists it will return the existing instance.
	 */
	private Container makeContainerOnTree (final ContentNode parentNode, final String id, final String title) {
		final ContentNode node = this.contentTree.getNode(id);
		if (node != null) return node.getContainer();

		final Container container = new Container();
		container.setClazz(new DIDLObject.Class("object.container"));
		container.setId(id);
		container.setParentID(parentNode.getId());
		container.setTitle(title);
		container.setRestricted(true);
		container.setWriteStatus(WriteStatus.NOT_WRITABLE);
		container.setChildCount(Integer.valueOf(0));

		final Container parentContainer = parentNode.getContainer();
		synchronized (parentContainer) {
			parentContainer.addContainer(container);
			parentContainer.setChildCount(Integer.valueOf(parentContainer.getChildCount().intValue() + 1));
		}
		this.contentTree.addNode(new ContentNode(id, container));

		return container;
	}

	private void makeItemInContainer (final MediaFormat format, final Container parent, final File file, final String title) {
		final String id = contentId(format.getContentGroup(), file);
		if (hasItemWithId(parent, id)) return;

		final Res res = new Res(formatToMime(format), Long.valueOf(file.length()), this.externalHttpContext + "/" + id);
		res.setSize(file.length());

		final Item item;
		switch (format.getContentGroup()) {
			case VIDEO:
				//res.setDuration(formatDuration(durationMillis));
				//res.setResolution(resolutionXbyY);
				item = new VideoItem(id, parent, title, "", res);
				findSubtitles(file, format, item);
				break;
			case IMAGE:
				//res.setResolution(resolutionXbyY);
				item = new ImageItem(id, parent, title, "", res);
				break;
			case AUDIO:
				//res.setDuration(formatDuration(durationMillis));
				item = new AudioItem(id, parent, title, "", res);
				break;
			default:
				throw new IllegalArgumentException();
		}

		findMetadata(file, item);
		findArt(file, format, item);

		synchronized (parent) {
			parent.addItem(item);
			parent.setChildCount(Integer.valueOf(parent.getChildCount().intValue() + 1));
		}
		this.contentTree.addNode(new ContentNode(item.getId(), item, file));
	}

	private static void findMetadata (final File file, final Item item) {
		final Metadata md = MetadataReader.read(file);
		if (md == null) return;
		if (md.getArtist() != null) item.addProperty(new DIDLObject.Property.UPNP.ARTIST(new PersonWithRole(md.getArtist())));
		if (md.getAlbum() != null) item.addProperty(new DIDLObject.Property.UPNP.ALBUM(md.getAlbum()));
	}

	private void findArt (final File mediaFile, final MediaFormat mediaFormat, final Item item) {
		final Res artRes = findArtRes(mediaFile, mediaFormat.getContentGroup());
		if (artRes == null) return;
		item.addResource(artRes);
	}

	private Res findArtRes (final File mediaFile, final ContentGroup mediaContentGroup) {
		final File artFile = CoverArtHelper.findCoverArt(mediaFile);
		if (artFile == null) return null;

		final MediaFormat artFormat = MediaFormat.identify(artFile);
		if (artFormat == null) {
			LOG.warn("Ignoring art file of unsupported type: {}", artFile);
			return null;
		}
		final MimeType artMimeType = formatToMime(artFormat);

		final String artId = contentId(mediaContentGroup, artFile);
		this.contentTree.addNode(new ContentNode(artId, null, artFile));
		return new Res(artMimeType, Long.valueOf(artFile.length()), this.externalHttpContext + "/" + artId);
	}

	private void findSubtitles (final File file, final MediaFormat format, final Item item) {
		for (final String name : file.getParentFile().list(new BasenameFilter(file))) {
			if (MediaFormat.identify(name) != null) continue;
			if (name.toLowerCase(Locale.UK).endsWith(".srt")) {
				addSubtitlesSrt(format, item, new File(file.getParent(), name));
			}
		}
	}

	private void addSubtitlesSrt (final MediaFormat format, final Item videoItem, final File srtFile) {
		final String srtId = contentId(format.getContentGroup(), srtFile);
		final MimeType srtMimeType = new MimeType("text", "srt");
		final Res srtRes = new Res(srtMimeType, Long.valueOf(srtFile.length()), this.externalHttpContext + "/" + srtId);
		this.contentTree.addNode(new ContentNode(srtId, null, srtFile));

		videoItem.addResource(srtRes);
	}

	private static String contentId (final ContentGroup type, final File file) {
		return type.getItemIdPrefix() + (HashHelper.sha1(file.getAbsolutePath()) + "-" + getSafeName(file));
	}

	private static String getSafeName (final File file) {
		return file.getName().replaceAll("[^a-zA-Z0-9]", "_");
	}

	private static MimeType formatToMime (final MediaFormat format) {
		final String mime = format.getMime();
		return new MimeType(mime.substring(0, mime.indexOf('/')), mime.substring(mime.indexOf('/') + 1));
	}

	private static boolean hasItemWithId (final Container parent, final String id) {
		final List<Item> items = parent.getItems();
		if (items == null) return false;
		synchronized (items) {
			for (final Item item : items) {
				if (id.equals(item.getId())) return true;
			}
		}
		return false;
	}

	private enum DIDLObjectOrder implements Comparator<DIDLObject> {
		TITLE {
			@Override
			public int compare (final DIDLObject a, final DIDLObject b) {
				return a.getTitle().compareToIgnoreCase(b.getTitle());
			}
		},
		ID {
			@Override
			public int compare (final DIDLObject a, final DIDLObject b) {
				return a.getId().compareTo(b.getId());
			}
		},
		CREATOR {
			@Override
			public int compare (final DIDLObject a, final DIDLObject b) {
				return a.getCreator().compareToIgnoreCase(b.getCreator());
			}
		};

		@Override
		public abstract int compare (DIDLObject a, DIDLObject b);
	}

	private final class BasenameFilter implements FilenameFilter {

		private final String baseName;

		public BasenameFilter (final File file) {
			this.baseName = FilenameUtils.getBaseName(file.getName());
		}

		@Override
		public boolean accept (final File dir, final String name) {
			return name.startsWith(this.baseName);
		}
	}

}
