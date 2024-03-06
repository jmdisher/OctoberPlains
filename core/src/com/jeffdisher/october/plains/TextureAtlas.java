package com.jeffdisher.october.plains;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.utils.Assert;


public class TextureAtlas
{
	public static TextureAtlas loadAtlas(GL20 gl
			, String[] names
			, String[] secondaryNames
			, String playerTextureName
			, String debrisTextureName
	) throws IOException
	{
		// We store all the textures in the same atlas but some of them are special versus some looked up by index.
		String[] combinedNames = new String[names.length + 2];
		System.arraycopy(names, 0, combinedNames, 0, names.length);
		combinedNames[names.length] = playerTextureName;
		combinedNames[names.length + 1] = debrisTextureName;
		
		int texturesPerRow = _texturesPerRow(combinedNames.length);
		
		// We will assume a fixed texture size of 32-square.
		int eachTextureEdge = 32;
		int texture = _createTextureAtlas(gl, combinedNames, texturesPerRow, eachTextureEdge);
		int secondaryTexturesPerRow = _texturesPerRow(secondaryNames.length);
		int secondaryTexture = _createTextureAtlas(gl, secondaryNames, secondaryTexturesPerRow, eachTextureEdge);
		int indexOfPlayer = names.length;
		int indexOfDebris = indexOfPlayer + 1;
		return new TextureAtlas(texture, secondaryTexture, texturesPerRow, secondaryTexturesPerRow, indexOfPlayer, indexOfDebris);
	}


	private static int _texturesPerRow(int textureCount)
	{
		// We essentially just want the base2 logarithm of the array length rounded to the nearest power of 2.
		// (a faster and generalizable algorithm using leading zeros could be used but is less obvious than this, for now)
		int texturesPerRow = 1;
		if (textureCount > 1)
		{
			texturesPerRow = 2;
			if (textureCount > 4)
			{
				texturesPerRow = 4;
				if (textureCount > 16)
				{
					texturesPerRow = 8;
					Assert.assertTrue(textureCount <= 64);
				}
			}
		}
		return texturesPerRow;
	}

	private static int _createTextureAtlas(GL20 gl, String[] imageNames, int texturesPerRow, int eachTextureEdge) throws IOException
	{
		int width = texturesPerRow * eachTextureEdge;
		int height = texturesPerRow * eachTextureEdge;
		
		// 4 bytes per pixel since we are storing pixels as RGBA.
		int bytesToAllocate = width * height * 4;
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(bytesToAllocate);
		textureBufferData.order(ByteOrder.nativeOrder());
		
		// Load all the images and walk across them to fill the buffer.
		BufferedImage loadedTextures[] = new BufferedImage[imageNames.length];
		for (int i = 0; i < imageNames.length; ++i)
		{
			String name = imageNames[i];
			
			// TODO:  Change this when we have more than one texture and need to actually build the atlas.
			FileHandle unknownTextureFile = Gdx.files.internal(name);
			BufferedImage loadedTexture = ImageIO.read(unknownTextureFile.read());
			// We require all textures to be of fixed square size.
			Assert.assertTrue(loadedTexture.getWidth() == eachTextureEdge);
			Assert.assertTrue(loadedTexture.getHeight() == eachTextureEdge);
			loadedTextures[i] = loadedTexture;
		}
		
		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				// Figure out which texture to pull from.
				int textureIndex = (y / eachTextureEdge * texturesPerRow) + (x / eachTextureEdge);
				if (textureIndex < loadedTextures.length)
				{
					BufferedImage loadedTexture = loadedTextures[textureIndex];
					int localX = x % eachTextureEdge;
					int localY = y % eachTextureEdge;
					int pixel = loadedTexture.getRGB(localX, localY);
					// This data is pulled out as ARGB but we need to upload it as RGBA.
					byte a = (byte)((0xFF000000 & pixel) >> 24);
					byte r = (byte)((0x00FF0000 & pixel) >> 16);
					byte g = (byte)((0x0000FF00 & pixel) >> 8);
					byte b = (byte) (0x000000FF & pixel);
					textureBufferData.put(new byte[] { r, g, b, a });
				}
				else
				{
					textureBufferData.put(new byte[4]);
				}
			}
		}
		((java.nio.Buffer) textureBufferData).flip();
		
		// Create the texture and upload.
		int texture = gl.glGenTexture();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, width, height, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
		gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		return texture;
	}


	public final int texture;
	public final int secondaryTexture;
	public final float coordinateSize;
	public final float secondaryCoordinateSize;
	private final int _texturesPerRow;
	private final int _secondaryTexturesPerRow;
	private final int _indexOfPlayer;
	private final int _indexOfDebris;

	private TextureAtlas(int texture, int secondaryTexture, int texturesPerRow, int secondaryTexturesPerRow, int indexOfPlayer, int indexOfDebris)
	{
		this.texture = texture;
		this.secondaryTexture = secondaryTexture;
		this.coordinateSize = 1.0f / (float)texturesPerRow;
		this.secondaryCoordinateSize = 1.0f / (float)secondaryTexturesPerRow;
		_texturesPerRow = texturesPerRow;
		_secondaryTexturesPerRow = secondaryTexturesPerRow;
		_indexOfPlayer = indexOfPlayer;
		_indexOfDebris = indexOfDebris;
	}

	/**
	 * Returns the UV base coordinates of the texture with the given index.
	 * 
	 * @param index The index of the texture, relative to the load order.
	 * @return {u, v} of texture base coordinates.
	 */
	public float[] baseOfTexture(int index)
	{
		Assert.assertTrue(index < _indexOfPlayer);
		return _baseOfTexture(index);
	}

	/**
	 * Returns the UV base coordinates of the secondary texture with the given index.
	 * 
	 * @param index The index of the texture, relative to the load order.
	 * @return {u, v} of texture base coordinates.
	 */
	public float[] baseOfSecondaryTexture(int index)
	{
		int row = index / _secondaryTexturesPerRow;
		int column = index % _secondaryTexturesPerRow;
		float u = this.secondaryCoordinateSize * (float)column;
		float v = this.secondaryCoordinateSize * (float)row;
		return new float[] {u, v};
	}


	public float[] baseOfPlayerTexture()
	{
		return _baseOfTexture(_indexOfPlayer);
	}

	public float[] baseOfDebrisTexture()
	{
		return _baseOfTexture(_indexOfDebris);
	}


	private float[] _baseOfTexture(int index)
	{
		int row = index / _texturesPerRow;
		int column = index % _texturesPerRow;
		float u = this.coordinateSize * (float)column;
		float v = this.coordinateSize * (float)row;
		return new float[] {u, v};
	}
}
