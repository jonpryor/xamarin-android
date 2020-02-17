using System;
using System.Collections.Generic;
using System.IO;

namespace Xamarin.Android.Prepare
{
	[TPN]
	class libzip_nih_at_libzip_TPN : ThirdPartyNotice
	{
		static readonly Uri url = new Uri ("https://github.com/nih-at/libzip/");
		static readonly string licenseFile = Path.Combine (GetNugetLocation (),
			"xamarin.libzipsharp", LibZipSharp_xamarin_LibZipSharp_TPN.LibZipSharpVersion (),
			"Licences", "libzip", "LICENSE");

		public override string LicenseFile => licenseFile;
		public override string Name => "nih-at/libzip";
		public override Uri SourceUrl => url;
		public override string LicenseText => null;

		public override bool Include (bool includeExternalDeps, bool includeBuildDeps) => includeExternalDeps;

		private static string GetNugetLocation()
		{
			string nugetPackagesOverride = Environment.GetEnvironmentVariable ("NUGET_PACKAGES");
			if (!String.IsNullOrEmpty (nugetPackagesOverride)) {
				return nugetPackagesOverride;
			}

			return Path.Combine (BuildPaths.XamarinAndroidSourceRoot,"packages");
		}
	}
}
