{
  description = "p2p-self-probe: can a phone connect to itself over Wi-Fi Direct?";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      inherit (nixpkgs) lib;
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      eachSystem = f: lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
      android = pkgs: import ./default.nix { inherit pkgs nixpkgs; };
    in
    {
      packages = eachSystem (
        pkgs:
        let
          app = android pkgs;
        in
        {
          default = app.apk;
        }
      );

      checks = eachSystem (
        pkgs:
        let
          app = android pkgs;
        in
        {
          # Compiling is the check: a break in src/ fails `nix flake check`
          # instead of waiting for a manual build.
          apk = app.apk;

          format = pkgs.runCommand "p2p-self-probe-format-check" { nativeBuildInputs = [ pkgs.nixfmt ]; } ''
            nixfmt --check ${./flake.nix} ${./default.nix}
            touch $out
          '';
        }
      );

      devShells = eachSystem (pkgs: {
        default = (android pkgs).shell;
      });

      formatter = eachSystem (pkgs: pkgs.nixfmt);
    };
}
