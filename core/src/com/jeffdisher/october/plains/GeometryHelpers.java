package com.jeffdisher.october.plains;

import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.EntityLocation;
import com.jeffdisher.october.types.EntityVolume;


/**
 * Misc helper methods.
 */
public class GeometryHelpers
{
	public static AbsoluteLocation getCentreAtFeet(Entity entity, EntityVolume volume)
	{
		EntityLocation entityLocation = entity.location();
		// (we want the block under our centre).
		float widthOffset = volume.width() / 2.0f;
		EntityLocation centre = new EntityLocation(entityLocation.x() + widthOffset, entityLocation.y() + widthOffset, entityLocation.z());
		return centre.getBlockLocation();
	}
}
