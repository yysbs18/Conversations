package eu.siacs.conversations.services;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.OnAdvancedStreamFeaturesLoaded;
import eu.siacs.conversations.xmpp.XmppConnection;
import rocks.xmpp.addr.Jid;

public class AvatarService implements OnAdvancedStreamFeaturesLoaded {

	private static final int FG_COLOR = 0xFFFAFAFA;
	private static final int TRANSPARENT = 0x00000000;
	private static final int PLACEHOLDER_COLOR = 0xFF202020;

	private static final String PREFIX_CONTACT = "contact";
	private static final String PREFIX_CONVERSATION = "conversation";
	private static final String PREFIX_ACCOUNT = "account";
	private static final String PREFIX_GENERIC = "generic";

	final private ArrayList<Integer> sizes = new ArrayList<>();
	final private HashMap<String,Set<String>> conversationDependentKeys = new HashMap<>();

	protected XmppConnectionService mXmppConnectionService = null;

	public AvatarService(XmppConnectionService service) {
		this.mXmppConnectionService = service;
	}

	private Bitmap get(final Contact contact, final int size, boolean cachedOnly) {
		if (contact.isSelf()) {
			return get(contact.getAccount(),size,cachedOnly);
		}
		final String KEY = key(contact, size);
		Bitmap avatar = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (avatar != null || cachedOnly) {
			return avatar;
		}
		if (contact.getProfilePhoto() != null) {
			avatar = mXmppConnectionService.getFileBackend().cropCenterSquare(Uri.parse(contact.getProfilePhoto()), size);
		}
		if (avatar == null && contact.getAvatar() != null) {
			avatar = mXmppConnectionService.getFileBackend().getAvatar(contact.getAvatar(), size);
		}
		if (avatar == null) {
            avatar = get(contact.getDisplayName(), contact.getJid().asBareJid().toString(), size, cachedOnly);
		}
		this.mXmppConnectionService.getBitmapCache().put(KEY, avatar);
		return avatar;
	}

	public Bitmap getRoundedShortcut(final Contact contact) {
		DisplayMetrics metrics = mXmppConnectionService.getResources().getDisplayMetrics();
		int size = Math.round(metrics.density * 48);
		Bitmap bitmap = get(contact,size);
		Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(output);

		final Paint paint = new Paint();
		final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());

		paint.setAntiAlias(true);
		canvas.drawARGB(0, 0, 0, 0);
		canvas.drawCircle(bitmap.getWidth() / 2, bitmap.getHeight() / 2, bitmap.getWidth() / 2, paint);
		paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
		canvas.drawBitmap(bitmap, rect, rect, paint);
		return output;
	}

	public Bitmap get(final MucOptions.User user, final int size, boolean cachedOnly) {
		Contact c = user.getContact();
		if (c != null && (c.getProfilePhoto() != null || c.getAvatar() != null || user.getAvatar() == null)) {
			return get(c, size, cachedOnly);
		} else {
			return getImpl(user, size, cachedOnly);
		}
	}

	private Bitmap getImpl(final MucOptions.User user, final int size, boolean cachedOnly) {
		final String KEY = key(user, size);
		Bitmap avatar = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (avatar != null || cachedOnly) {
			return avatar;
		}
		if (user.getAvatar() != null) {
			avatar = mXmppConnectionService.getFileBackend().getAvatar(user.getAvatar(), size);
		}
		if (avatar == null) {
			Contact contact = user.getContact();
			if (contact != null) {
				avatar = get(contact, size, cachedOnly);
			} else {
				String seed = user.getRealJid() != null ? user.getRealJid().asBareJid().toString() : null;
				avatar = get(user.getName(), seed, size, cachedOnly);
			}
		}
		this.mXmppConnectionService.getBitmapCache().put(KEY, avatar);
		return avatar;
	}

	public void clear(Contact contact) {
		synchronized (this.sizes) {
			for (Integer size : sizes) {
				this.mXmppConnectionService.getBitmapCache().remove(
						key(contact, size));
			}
		}
		for(Conversation conversation : mXmppConnectionService.findAllConferencesWith(contact)) {
			clear(conversation);
		}
	}

	private String key(Contact contact, int size) {
		synchronized (this.sizes) {
			if (!this.sizes.contains(size)) {
				this.sizes.add(size);
			}
		}
		return PREFIX_CONTACT +
				'\0' +
				contact.getAccount().getJid().asBareJid() +
				'\0' +
				emptyOnNull(contact.getJid()) +
				'\0' +
				size;
	}

	private String key(MucOptions.User user, int size) {
		synchronized (this.sizes) {
			if (!this.sizes.contains(size)) {
				this.sizes.add(size);
			}
		}
		return PREFIX_CONTACT +
				'\0' +
				user.getAccount().getJid().asBareJid() +
				'\0' +
				emptyOnNull(user.getFullJid()) +
				'\0' +
				emptyOnNull(user.getRealJid()) +
				'\0' +
				size;
	}

	public Bitmap get(ListItem item, int size) {
		return get(item,size,false);
	}

	public Bitmap get(ListItem item, int size, boolean cachedOnly) {
		if (item instanceof Contact) {
			return get((Contact) item, size,cachedOnly);
		} else if (item instanceof Bookmark) {
			Bookmark bookmark = (Bookmark) item;
			if (bookmark.getConversation() != null) {
				return get(bookmark.getConversation(), size, cachedOnly);
			} else {
				String seed = bookmark.getJid() != null ? bookmark.getJid().asBareJid().toString() : null;
				return get(bookmark.getDisplayName(), seed, size, cachedOnly);
			}
		} else {
			String seed = item.getJid() != null ? item.getJid().asBareJid().toString() : null;
			return get(item.getDisplayName(), seed, size, cachedOnly);
		}
	}

	public Bitmap get(Conversation conversation, int size) {
		return get(conversation,size,false);
	}

	public Bitmap get(Conversation conversation, int size, boolean cachedOnly) {
		if (conversation.getMode() == Conversation.MODE_SINGLE) {
			return get(conversation.getContact(), size, cachedOnly);
		} else {
			return get(conversation.getMucOptions(), size, cachedOnly);
		}
	}

	public void clear(Conversation conversation) {
		if (conversation.getMode() == Conversation.MODE_SINGLE) {
			clear(conversation.getContact());
		} else {
			clear(conversation.getMucOptions());
			synchronized (this.conversationDependentKeys) {
				Set<String> keys = this.conversationDependentKeys.get(conversation.getUuid());
				if (keys == null) {
					return;
				}
				LruCache<String, Bitmap> cache = this.mXmppConnectionService.getBitmapCache();
				for(String key : keys) {
					cache.remove(key);
				}
				keys.clear();
			}
		}
	}

	private Bitmap get(MucOptions mucOptions, int size,  boolean cachedOnly) {
		final String KEY = key(mucOptions, size);
		Bitmap bitmap = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (bitmap != null || cachedOnly) {
			return bitmap;
		}
		final List<MucOptions.User> users = mucOptions.getUsersRelevantForNameAndAvatar();
		if (users.size() == 0) {
			Conversation c = mucOptions.getConversation();
			bitmap = getImpl(c.getName().toString(),c.getJid().asBareJid().toString(),size);
		} else {
			bitmap = getImpl(users,size);
		}
		this.mXmppConnectionService.getBitmapCache().put(KEY, bitmap);
		return bitmap;
	}

	private Bitmap get(List<MucOptions.User> users, int size, boolean cachedOnly) {
		final String KEY = key(users, size);
		Bitmap bitmap = this.mXmppConnectionService.getBitmapCache().get(KEY);
		if (bitmap != null || cachedOnly) {
			return bitmap;
		}
		bitmap = getImpl(users, size);
		this.mXmppConnectionService.getBitmapCache().put(KEY,bitmap);
		return bitmap;
	}

	private Bitmap getImpl(List<MucOptions.User> users, int size) {
		int count = users.size();
		Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		bitmap.eraseColor(TRANSPARENT);
		if (count == 0) {
			throw new AssertionError("Unable to draw tiles for 0 users");
		} else if (count == 1) {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size);
			drawTile(canvas, users.get(0).getAccount(), size / 2 + 1, 0, size, size);
		} else if (count == 2) {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size);
			drawTile(canvas, users.get(1), size / 2 + 1, 0, size, size);
		} else if (count == 3) {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size);
			drawTile(canvas, users.get(1), size / 2 + 1, 0, size, size / 2 - 1);
			drawTile(canvas, users.get(2), size / 2 + 1, size / 2 + 1, size,
					size);
		} else if (count == 4) {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size / 2 - 1);
			drawTile(canvas, users.get(1), 0, size / 2 + 1, size / 2 - 1, size);
			drawTile(canvas, users.get(2), size / 2 + 1, 0, size, size / 2 - 1);
			drawTile(canvas, users.get(3), size / 2 + 1, size / 2 + 1, size,
					size);
		} else {
			drawTile(canvas, users.get(0), 0, 0, size / 2 - 1, size / 2 - 1);
			drawTile(canvas, users.get(1), 0, size / 2 + 1, size / 2 - 1, size);
			drawTile(canvas, users.get(2), size / 2 + 1, 0, size, size / 2 - 1);
			drawTile(canvas, "\u2026", PLACEHOLDER_COLOR, size / 2 + 1, size / 2 + 1,
					size, size);
		}
		return bitmap;
	}

	public void clear(MucOptions options) {
		synchronized (this.sizes) {
			for (Integer size : sizes) {
				this.mXmppConnectionService.getBitmapCache().remove(key(options, size));
			}
		}
	}

	private String key(MucOptions options, int size) {
		synchronized (this.sizes) {
			if (!this.sizes.contains(size)) {
				this.sizes.add(size);
			}
		}
		return PREFIX_CONVERSATION + "_" + options.getConversation().getUuid()
				+ "_" + String.valueOf(size);
	}

	private String key(List<MucOptions.User> users, int size) {
		final Conversation conversation = users.get(0).getConversation();
		StringBuilder builder = new StringBuilder("TILE_");
		builder.append(conversation.getUuid());

		for(MucOptions.User user : users) {
			builder.append("\0");
			builder.append(emptyOnNull(user.getRealJid()));
			builder.append("\0");
			builder.append(emptyOnNull(user.getFullJid()));
		}
		builder.append('\0');
		builder.append(size);
		final String key = builder.toString();
		synchronized (this.conversationDependentKeys) {
			Set<String> keys;
			if (this.conversationDependentKeys.containsKey(conversation.getUuid())) {
				keys = this.conversationDependentKeys.get(conversation.getUuid());
			} else {
				keys = new HashSet<>();
				this.conversationDependentKeys.put(conversation.getUuid(),keys);
			}
			keys.add(key);
		}
		return key;
	}

	public Bitmap get(Account account, int size) {
		return get(account, size, false);
	}

	public Bitmap get(Account account, int size, boolean cachedOnly) {
		final String KEY = key(account, size);
		Bitmap avatar = mXmppConnectionService.getBitmapCache().get(KEY);
		if (avatar != null || cachedOnly) {
			return avatar;
		}
		avatar = mXmppConnectionService.getFileBackend().getAvatar(account.getAvatar(), size);
		if (avatar == null) {
			avatar = get(account.getJid().asBareJid().toString(), null, size,false);
		}
		mXmppConnectionService.getBitmapCache().put(KEY, avatar);
		return avatar;
	}

	public Bitmap get(Message message, int size, boolean cachedOnly) {
		final Conversation conversation = message.getConversation();
		if (message.getType() == Message.TYPE_STATUS && message.getCounterparts() != null && message.getCounterparts().size() > 1) {
			return get(message.getCounterparts(),size,cachedOnly);
		} else if (message.getStatus() == Message.STATUS_RECEIVED) {
			Contact c = message.getContact();
			if (c != null && (c.getProfilePhoto() != null || c.getAvatar() != null)) {
				return get(c, size, cachedOnly);
			} else if (message.getConversation().getMode() == Conversation.MODE_MULTI){
				final Jid trueCounterpart = message.getTrueCounterpart();
				MucOptions.User user;
				if (trueCounterpart != null) {
					user = conversation.getMucOptions().findUserByRealJid(trueCounterpart);
				} else {
					user = conversation.getMucOptions().findUserByFullJid(message.getCounterpart());
				}
				if (user != null) {
					return getImpl(user,size,cachedOnly);
				}
			} else if (c != null) {
				return get(c, size, cachedOnly);
			}
			Jid tcp = message.getTrueCounterpart();
			String seed = tcp != null ? tcp.asBareJid().toString() :null;
			return get(UIHelper.getMessageDisplayName(message), seed, size, cachedOnly);
		} else  {
			return get(conversation.getAccount(), size, cachedOnly);
		}
	}

	public void clear(Account account) {
		synchronized (this.sizes) {
			for (Integer size : sizes) {
				this.mXmppConnectionService.getBitmapCache().remove(key(account, size));
			}
		}
	}

	public void clear(MucOptions.User user) {
		synchronized (this.sizes) {
			for (Integer size : sizes) {
				this.mXmppConnectionService.getBitmapCache().remove(key(user, size));
			}
		}
	}

	private String key(Account account, int size) {
		synchronized (this.sizes) {
			if (!this.sizes.contains(size)) {
				this.sizes.add(size);
			}
		}
		return PREFIX_ACCOUNT + "_" + account.getUuid() + "_"
				+ String.valueOf(size);
	}

	/*public Bitmap get(String name, int size) {
		return get(name,null, size,false);
	}*/

	public Bitmap get(final String name, String seed, final int size, boolean cachedOnly) {
		final String KEY = key(seed == null ? name : seed, size);
		Bitmap bitmap = mXmppConnectionService.getBitmapCache().get(KEY);
		if (bitmap != null || cachedOnly) {
			return bitmap;
		}
		bitmap = getImpl(name, seed, size);
		mXmppConnectionService.getBitmapCache().put(KEY, bitmap);
		return bitmap;
	}

	private Bitmap getImpl(final String name, final String seed, final int size) {
		Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		final String trimmedName = name == null ? "" : name.trim();
		drawTile(canvas, trimmedName, seed, 0, 0, size, size);
		return bitmap;
	}

	private String key(String name, int size) {
		synchronized (this.sizes) {
			if (!this.sizes.contains(size)) {
				this.sizes.add(size);
			}
		}
		return PREFIX_GENERIC + "_" + name + "_" + String.valueOf(size);
	}

	private boolean drawTile(Canvas canvas, String letter, int tileColor, int left, int top, int right, int bottom) {
		letter = letter.toUpperCase(Locale.getDefault());
		Paint tilePaint = new Paint(), textPaint = new Paint();
		tilePaint.setColor(tileColor);
		textPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
		textPaint.setColor(FG_COLOR);
		textPaint.setTypeface(Typeface.create("sans-serif-light",
				Typeface.NORMAL));
		textPaint.setTextSize((float) ((right - left) * 0.8));
		Rect rect = new Rect();

		canvas.drawRect(new Rect(left, top, right, bottom), tilePaint);
		textPaint.getTextBounds(letter, 0, 1, rect);
		float width = textPaint.measureText(letter);
		canvas.drawText(letter, (right + left) / 2 - width / 2, (top + bottom)
				/ 2 + rect.height() / 2, textPaint);
		return true;
	}

	private boolean drawTile(Canvas canvas, MucOptions.User user, int left, int top, int right, int bottom) {
		Contact contact = user.getContact();
		if (contact != null) {
			Uri uri = null;
			if (contact.getProfilePhoto() != null) {
				uri = Uri.parse(contact.getProfilePhoto());
			} else if (contact.getAvatar() != null) {
				uri = mXmppConnectionService.getFileBackend().getAvatarUri(
						contact.getAvatar());
			}
			if (drawTile(canvas, uri, left, top, right, bottom)) {
				return true;
			}
		} else if (user.getAvatar() != null) {
			Uri uri = mXmppConnectionService.getFileBackend().getAvatarUri(user.getAvatar());
			if (drawTile(canvas, uri, left, top, right, bottom)) {
				return true;
			}
		}
		if (contact != null) {
			String seed = contact.getJid().asBareJid().toString();
			drawTile(canvas, contact.getDisplayName(), seed, left, top, right, bottom);
		} else {
			String seed = user.getRealJid() == null ? null : user.getRealJid().asBareJid().toString();
			drawTile(canvas, user.getName(), seed, left, top, right, bottom);
		}
		return true;
	}

	private boolean drawTile(Canvas canvas, Account account, int left, int top, int right, int bottom) {
		String avatar = account.getAvatar();
		if (avatar != null) {
			Uri uri = mXmppConnectionService.getFileBackend().getAvatarUri(avatar);
			if (uri != null) {
				if (drawTile(canvas, uri, left, top, right, bottom)) {
					return true;
				}
			}
		}
		String name = account.getJid().asBareJid().toString();
		return drawTile(canvas, name, name, left, top, right, bottom);
	}

	private boolean drawTile(Canvas canvas, String name, String seed, int left, int top, int right, int bottom) {
		if (name != null) {
			final String letter = getFirstLetter(name);
			final int color = UIHelper.getColorForName(seed == null ? name : seed);
			drawTile(canvas, letter, color, left, top, right, bottom);
			return true;
		}
		return false;
	}

	private static String getFirstLetter(String name) {
		for(Character c : name.toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				return c.toString();
			}
		}
		return "X";
	}

	private boolean drawTile(Canvas canvas, Uri uri, int left, int top, int right, int bottom) {
		if (uri != null) {
			Bitmap bitmap = mXmppConnectionService.getFileBackend()
					.cropCenter(uri, bottom - top, right - left);
			if (bitmap != null) {
				drawTile(canvas, bitmap, left, top, right, bottom);
				return true;
			}
		}
		return false;
	}

	private boolean drawTile(Canvas canvas, Bitmap bm, int dstleft, int dsttop, int dstright, int dstbottom) {
		Rect dst = new Rect(dstleft, dsttop, dstright, dstbottom);
		canvas.drawBitmap(bm, null, dst, null);
		return true;
	}

	@Override
	public void onAdvancedStreamFeaturesAvailable(Account account) {
		XmppConnection.Features features = account.getXmppConnection().getFeatures();
		if (features.pep() && !features.pepPersistent()) {
			Log.d(Config.LOGTAG,account.getJid().asBareJid()+": has pep but is not persistent");
			if (account.getAvatar() != null) {
				mXmppConnectionService.republishAvatarIfNeeded(account);
			}
		}
	}

	private static String emptyOnNull(@Nullable Jid value) {
		return value == null ? "" : value.toString();
	}
}
