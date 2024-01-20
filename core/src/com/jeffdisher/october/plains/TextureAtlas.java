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
	public static TextureAtlas loadAtlas(GL20 gl, String[] names) throws IOException
	{
		// We essentially just want the base2 logarithm of the array length rounded to the nearest power of 2.
		// (a faster and generalizable algorithm using leading zeros could be used but is less obvious than this, for now)
		int texturesPerRow = 1;
		if (names.length > 1)
		{
			texturesPerRow = 2;
			if (names.length > 4)
			{
				texturesPerRow = 4;
				if (names.length > 16)
				{
					texturesPerRow = 8;
					Assert.assertTrue(names.length <= 64);
				}
			}
		}
		
		// We will assume a fixed texture size of 32-square.
		int eachTextureEdge = 32;
		int width = texturesPerRow * eachTextureEdge;
		int height = texturesPerRow * eachTextureEdge;
		
		// 4 bytes per pixel since we are storing pixels as RGBA.
		int bytesToAllocate = width * height * 4;
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(bytesToAllocate);
		textureBufferData.order(ByteOrder.nativeOrder());
		
		// Load all the images and walk across them to fill the buffer.
		BufferedImage loadedTextures[] = new BufferedImage[names.length];
		for (int i = 0; i < names.length; ++i)
		{
			String name = names[i];
			
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
		return new TextureAtlas(texture, texturesPerRow);
	}


	public final int texture;
	public final float coordinateSize;
	private final int _texturesPerRow;

	private TextureAtlas(int texture, int texturesPerRow)
	{
		this.texture = texture;
		this.coordinateSize = 1.0f / (float)texturesPerRow;
		_texturesPerRow = texturesPerRow;
	}

	/**
	 * Returns the UV base coordinates of the texture with the given index.
	 * 
	 * @param index The index of the texture, relative to the load order.
	 * @return {u, v} of texture base coordinates.
	 */
	public float[] baseOfTexture(int index)
	{
		int row = index / _texturesPerRow;
		int column = index % _texturesPerRow;
		float u = this.coordinateSize * (float)column;
		float v = this.coordinateSize * (float)row;
		return new float[] {u, v};
	}
}
