package com.jeffdisher.october.plains;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.utils.Assert;


/**
 * We currently store all text as individual textures in graphics memory (these may be combined or done a different way
 * in the future).  This manager tracks those textures and provides facility for rendering new ones.
 * This texture memory is periodically reclaimed if it gets too full.
 */
public class TextManager
{
	/**
	 * We will only try to purge unused text values if there are more than this many.
	 */
	public static final int TEXT_CACHE_TARGET_SIZE = 100;
	/**
	 * This is the largest number of textures we will try to purge in a single purge call (allows buffer reuse since it
	 * is off-heap).
	 */
	public static final int TEXT_CACHE_MAX_PURGE_PER_ATTEMPT = 64;

	private final GL20 _gl;
	private final Map<String, Element> _textTextures;
	private final Graphics2D _graphics;
	private final Font _font;
	private final java.awt.FontMetrics _fontMetrics;

	// Variables related to texture purging.
	private Set<String> _recentlyUsed;
	private final IntBuffer _purgeBuffer;

	public TextManager(GL20 gl)
	{
		_gl = gl;
		_textTextures = new HashMap<>();
		_purgeBuffer = ByteBuffer.allocateDirect(Integer.BYTES * TEXT_CACHE_MAX_PURGE_PER_ATTEMPT).asIntBuffer();
		
		// Our textures are 1-byte aligned so reduce the alignment.
		_gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
	
		// We want to get a shared graphics context which we will use for measuring the text size.
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		_graphics = image.createGraphics();
		_font = new Font("Arial", Font.BOLD, 64);
		_fontMetrics = _graphics.getFontMetrics(_font);
		// The graphics system either does some lazy loading or heavily depends on JIT since the first call is at least 10x the cost of later ones.
		// Hence, just draw "something" and ignore the result.
		_writtenImage("");
	}

	public Element lazilyLoadStringTexture(String string)
	{
		if (!_textTextures.containsKey(string))
		{
			// Lazily generate the texture and store it in the map.
			int labelTexture = _gl.glGenTexture();
			_gl.glBindTexture(GL20.GL_TEXTURE_2D, labelTexture);
			float aspectRatio = _renderTextToImage(labelTexture, string);
			// The text is always too wide so we will lie and say it is half this width (it just looks better).
			_textTextures.put(string, new Element(labelTexture, aspectRatio / 2.0f));
		}
		if (null != _recentlyUsed)
		{
			// If we are sampling active textures, add this one.
			_recentlyUsed.add(string);
		}
		return _textTextures.get(string);
	}

	/**
	 * Called periodically to allow the text manage to purge unused textures.
	 */
	public void allowTexturePurge()
	{
		if (null != _recentlyUsed)
		{
			// Texture sampling is active so purge anything we didn't see and disable sampling.
			int unreferencedCount = _textTextures.size() - _recentlyUsed.size();
			if (unreferencedCount > 0)
			{
				_purgeBuffer.clear();
				int purgeCount = 0;
				
				Iterator<String> iter = _textTextures.keySet().iterator();
				while (iter.hasNext())
				{
					String key = iter.next();
					if (!_recentlyUsed.contains(key))
					{
						// This hasn't been referenced since sampling.
						Element toPurge = _textTextures.get(key);
						Assert.assertTrue(_purgeBuffer.hasRemaining());
						_purgeBuffer.put(toPurge.textureObject);
						purgeCount += 1;
						iter.remove();
					}
					
					// Make sure we still have purge buffer space.
					if (purgeCount >= TEXT_CACHE_MAX_PURGE_PER_ATTEMPT)
					{
						break;
					}
				}
				_gl.glDeleteTextures(purgeCount, _purgeBuffer);
			}
			
			_recentlyUsed = null;
		}
		else if (_textTextures.size() > TEXT_CACHE_TARGET_SIZE)
		{
			// We want to try to purge text elements so start sampling what we use.
			_recentlyUsed = new HashSet<>();
		}
	}


	private float _renderTextToImage(int texture, String text)
	{
		BufferedImage image = _writtenImage(text);
		int width = image.getWidth();
		int height = image.getHeight();
		
		int channelsPerPixel = 2;
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(width * height * channelsPerPixel);
		textureBufferData.order(ByteOrder.nativeOrder());
		int[] rawData = new int[width * height];
		image.getRGB(0, 0, width, height, rawData, 0, width);
		for (int pixel : rawData)
		{
			// This data is pulled out as ARGB but we need to upload it as LA.
			// We draw white so just get any channel and the alpha.
			byte a = (byte)((0xFF000000 & pixel) >> 24);
			byte b = (byte) (0x000000FF & pixel);
			textureBufferData.put(new byte[] { b, a });
		}
		((java.nio.Buffer) textureBufferData).flip();
		
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
		_gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, width, height, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		_gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		return ((float)width / (float)height);
	}

	private BufferedImage _writtenImage(String text)
	{
		Rectangle2D rect = _fontMetrics.getStringBounds(text, _graphics);
		double width = rect.getWidth();
		if (width < 1.0)
		{
			width = 1.0;
		}
		double height = rect.getHeight();
		BufferedImage image = new BufferedImage((int)width, (int)height, BufferedImage.TYPE_INT_ARGB);
		Graphics graphics = image.getGraphics();
		graphics.setFont(_font);
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, 0, (int)height);
		return image;
	}


	public static record Element(int textureObject, float aspectRatio)
	{}
}
