package com.jeffdisher.october.plains;

import java.util.HashMap;
import java.util.Map;

import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.CuboidAddress;


/**
 * Contains the currently loaded cuboids on the client so that data can be queried easily.
 */
public class WorldCache
{
	private final Map<CuboidAddress, IReadOnlyCuboidData> _cuboids = new HashMap<>();

	public void setCuboid(IReadOnlyCuboidData cuboid)
	{
		_cuboids.put(cuboid.getCuboidAddress(), cuboid);
	}

	public void removeCuboid(CuboidAddress address)
	{
		_cuboids.remove(address);
	}

	public BlockProxy readBlock(AbsoluteLocation location)
	{
		IReadOnlyCuboidData cuboid = _cuboids.get(location.getCuboidAddress());
		return (null != cuboid)
				? new BlockProxy(location.getBlockAddress(), cuboid)
				: null
		;
	}
}
